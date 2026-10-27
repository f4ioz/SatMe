/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.sstv.SstvMode
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * « Agenda › SSTV »: an SSTV event entered as an appointment (a satellite, its
 * transmitter or a frequency of its own, the SSTV mode, a period), and the
 * receive chain the auto-pilot sets up for each of its passes. Here the
 * model, its file, and which passes it takes; [PiloteSstv] runs them.
 *
 * **Receive only.** Nothing here names a transmitter setting.
 */
object CampagneSstv {

    const val MAX = 40
    /** The history kept (passes done or set aside). */
    const val MAX_HISTORIQUE = 200
    /** A pass is the same pass again when its AOS moved less than this (new elements). */
    const val TOLERANCE_MS = 5 * 60_000L
    /** The recording goes on this long after LOS. */
    const val APRES_LOS_MS = 30_000L
    /** Longest campaign. */
    const val DUREE_MAX_MS = 62 * 86_400_000L

    const val AUTO = "Auto"
    /** The chain's sound: the rig's own (the IC-9700 by the network or its USB codec). */
    const val POSTE = "poste"
    /** The chain's sound: the SDR receiver. */
    const val SDR = "sdr"
    val MODULATIONS = listOf("FM", "NFM")

    /** The SSTV modes offered (« Auto »: the header says it). */
    val MODES: List<String> get() = listOf(AUTO) + SstvMode.ALL.map { it.name }

    /** What the auto-pilot does for each pass. */
    data class Chaine(
        /** A « Montage » profile applied first (its receive settings), "" none. */
        val profil: String = "",
        /** The rig tuned by CAT (Doppler) — connected by itself only when [connecterPoste]. */
        val poste: Boolean = true,
        val connecterPoste: Boolean = false,
        /** [POSTE], [SDR], or a sound card's name; "" leaves the sound as it is. */
        val source: String = POSTE,
        /** The mast follows — connected by itself only when [connecterRotor]. */
        val rotor: Boolean = true,
        val connecterRotor: Boolean = false,
        val enregistrer: Boolean = true,
        val sstv: Boolean = true,
        val annonce: Boolean = true,
        /** The mast parked after LOS. */
        val parquer: Boolean = false,
    )

    data class Campagne(
        val id: String,
        val nom: String,
        val catnum: Int,
        val satNom: String,
        /** The satellite's transmitter chosen (its description), "" with a frequency of its own. */
        val emetteur: String = "",
        /** The frequency of its own (sky Hz, 0: the transmitter's), its modulation, its bandwidth. */
        val freqHz: Long = 0L,
        val modulation: String = "FM",
        val largeurHz: Int = 15_000,
        /** [AUTO] or a [SstvMode] name: the decoder held on it. */
        val modeSstv: String = AUTO,
        val debutMs: Long,
        val finMs: Long,
        /** Times not known yet: whole days (the page sets 00:00 → 23:59). */
        val aConfirmer: Boolean = false,
        val elMin: Double = 10.0,
        /** « 36 s on / 2 min off »: said, not used. */
        val horaire: String = "",
        /** The chain set up this long before AOS (minutes). */
        val avantMin: Int = 2,
        val chaine: Chaine = Chaine(),
        val notes: String = "",
        /** A web page (the gallery…): shown as a link, nothing sent there. */
        val lien: String = "",
        val active: Boolean = true,
        val creeMs: Long = 0L,
        /** The « Activités à venir » activity it was made from in one click (its id), "" none. */
        val activite: String = "",
        /** Its period still follows the activity's (false once the owner changed it by hand). */
        val activiteSuit: Boolean = true,
    ) {
        val personnalisee: Boolean get() = freqHz > 0L
        /** The SSTV mode held, null: « Auto ». */
        val modeForce: SstvMode? get() = SstvMode.byName(modeSstv)
        val avantMs: Long get() = avantMin * 60_000L
    }

    enum class Statut { PREVU, CHEVAUCHE, EN_COURS, FAIT, ECARTE, ARRETE, INTERROMPU }

    /** One pass of a campaign as the plan sees it. */
    data class Prevu(val campagne: Campagne, val passage: SatPass, val statut: Statut, val raison: String = "")

    /** What became of a pass (the history, kept with the campaigns). */
    data class Trace(
        val campagne: String, val nom: String, val catnum: Int, val satNom: String,
        val aosMs: Long, val losMs: Long, val elMax: Double,
        val statut: Statut, val raison: String = "", val quandMs: Long = 0L,
        val enregistrement: String = "", val images: List<String> = emptyList(), val faits: List<String> = emptyList(),
    ) {
        fun json(): JSONObject = JSONObject().put("campagne", campagne).put("nom", nom).put("catnum", catnum).put("sat", satNom)
            .put("aos", aosMs).put("los", losMs).put("el", elMax).put("statut", statut.name).put("raison", raison).put("quand", quandMs)
            .put("enregistrement", enregistrement).put("images", JSONArray(images)).put("faits", JSONArray(faits))
    }

    // --------------------------------------------------------------- the passes

    /** [c]'s passes among [l]: its satellite, inside its period, high enough; soonest first. */
    fun passages(c: Campagne, l: List<SatPass>): List<SatPass> = l
        .filter { it.catalogNumber == c.catnum && it.maxElevationDeg >= c.elMin && it.losEpochMs > c.debutMs && it.aosEpochMs < c.finMs }
        .sortedBy { it.aosEpochMs }

    /** Is [t] about this pass of this campaign (its AOS moved a little by new elements)? */
    fun meme(t: Trace, c: Campagne, p: SatPass): Boolean =
        t.campagne == c.id && t.catnum == p.catalogNumber && abs(t.aosMs - p.aosEpochMs) <= TOLERANCE_MS

    /**
     * The plan: each active campaign's passes not over, soonest first. Those
     * already done or set aside keep what became of them; two passes at once
     * (another campaign's): the earlier goes on, the later is lost.
     */
    fun plan(campagnes: List<Campagne>, passagesDe: (Campagne) -> List<SatPass>, historique: List<Trace>,
             maintenant: Long, enCours: Pair<String, Long>? = null): List<Prevu> {
        val tous = campagnes.filter { it.active }.flatMap { c ->
            passages(c, passagesDe(c)).filter { it.losEpochMs + APRES_LOS_MS > maintenant }.map { c to it }
        }.sortedBy { it.second.aosEpochMs }
        var fin = Long.MIN_VALUE
        var nom = ""
        return tous.map { (c, p) ->
            val t = historique.lastOrNull { meme(it, c, p) }
            when {
                enCours != null && enCours.first == c.id && abs(enCours.second - p.aosEpochMs) <= TOLERANCE_MS -> {
                    fin = p.losEpochMs + APRES_LOS_MS; nom = c.nom; Prevu(c, p, Statut.EN_COURS)
                }
                t != null -> {
                    if (t.statut == Statut.FAIT || t.statut == Statut.ARRETE || t.statut == Statut.INTERROMPU) { fin = maxOf(fin, p.losEpochMs + APRES_LOS_MS); nom = c.nom }
                    Prevu(c, p, t.statut, t.raison)
                }
                p.aosEpochMs - c.avantMs < fin -> Prevu(c, p, Statut.CHEVAUCHE, "en même temps que « $nom »")
                else -> { fin = p.losEpochMs + APRES_LOS_MS; nom = c.nom; Prevu(c, p, Statut.PREVU) }
            }
        }
    }

    // ------------------------------------------------------------- checking

    private fun propre(s: String, n: Int) = s.filter { it >= ' ' && it != '<' && it != '>' }.trim().take(n)

    /** A link shown as such: http(s) only. */
    fun lienPropre(s: String): String = s.trim().takeIf { Regex("^https?://[^\\s<>\"']{3,300}$").matches(it) } ?: ""

    val ID = Regex("^[a-z0-9-]{1,40}$")

    /** A new campaign's id from its name. */
    fun nouvelId(nom: String, l: List<Campagne>): String {
        val base = java.text.Normalizer.normalize(nom.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(30).ifBlank { "campagne" }
        var id = base; var n = 2
        while (l.any { it.id == id }) id = base.take(34) + "-" + n++
        return id
    }

    /** [c] cleaned, or why it cannot be kept. */
    fun valide(c: Campagne): Result<Campagne> {
        if (c.catnum <= 0) return Result.failure(IllegalArgumentException("choisir le satellite"))
        if (c.finMs <= c.debutMs) return Result.failure(IllegalArgumentException("la fin doit venir après le début"))
        if (c.finMs - c.debutMs > DUREE_MAX_MS) return Result.failure(IllegalArgumentException("deux mois au plus"))
        if (c.freqHz != 0L && c.freqHz !in 1_000_000L..30_000_000_000L) return Result.failure(IllegalArgumentException("fréquence hors des bandes"))
        if (c.freqHz == 0L && c.emetteur.isBlank()) return Result.failure(IllegalArgumentException("choisir l'émetteur du satellite ou une fréquence"))
        val nom = propre(c.nom, 60).ifBlank { "SSTV " + propre(c.satNom, 30) }
        val ch = c.chaine
        return Result.success(c.copy(
            id = c.id.takeIf { ID.matches(it) } ?: "campagne",
            nom = nom, satNom = propre(c.satNom, 40), emetteur = propre(c.emetteur, 120),
            modulation = c.modulation.uppercase().takeIf { it in MODULATIONS } ?: "FM",
            largeurHz = c.largeurHz.coerceIn(3_000, 250_000),
            modeSstv = c.modeSstv.takeIf { it == AUTO || SstvMode.byName(it) != null } ?: AUTO,
            elMin = c.elMin.coerceIn(0.0, 90.0), horaire = propre(c.horaire, 80), avantMin = c.avantMin.coerceIn(0, 30),
            notes = propre(c.notes, 600), lien = lienPropre(c.lien), activite = propre(c.activite, 400),
            chaine = ch.copy(profil = ch.profil.takeIf { ID.matches(it) } ?: "", source = propre(ch.source, 120)),
        ))
    }

    // ------------------------------------------------------------------ the file

    fun json(c: Campagne): JSONObject = JSONObject().put("id", c.id).put("nom", c.nom).put("catnum", c.catnum).put("sat", c.satNom)
        .put("emetteur", c.emetteur).put("freqHz", c.freqHz).put("modulation", c.modulation).put("largeurHz", c.largeurHz)
        .put("mode", c.modeSstv).put("de", c.debutMs).put("a", c.finMs).put("aConfirmer", c.aConfirmer).put("elMin", c.elMin)
        .put("horaire", c.horaire).put("avantMin", c.avantMin).put("notes", c.notes).put("lien", c.lien).put("active", c.active).put("cree", c.creeMs)
        .put("activite", c.activite).put("activiteSuit", c.activiteSuit)
        .put("chaine", c.chaine.let { h -> JSONObject().put("profil", h.profil).put("poste", h.poste).put("connecterPoste", h.connecterPoste)
            .put("source", h.source).put("rotor", h.rotor).put("connecterRotor", h.connecterRotor).put("enregistrer", h.enregistrer)
            .put("sstv", h.sstv).put("annonce", h.annonce).put("parquer", h.parquer) })

    fun lisCampagne(o: JSONObject): Campagne? = runCatching {
        val h = o.optJSONObject("chaine") ?: JSONObject()
        val d = Chaine()
        valide(Campagne(id = o.optString("id"), nom = o.optString("nom"), catnum = o.optInt("catnum"), satNom = o.optString("sat"),
            emetteur = o.optString("emetteur"), freqHz = o.optLong("freqHz"), modulation = o.optString("modulation", "FM"),
            largeurHz = o.optInt("largeurHz", 15_000), modeSstv = o.optString("mode", AUTO), debutMs = o.optLong("de"), finMs = o.optLong("a"),
            aConfirmer = o.optBoolean("aConfirmer"), elMin = o.optDouble("elMin", 10.0), horaire = o.optString("horaire"),
            avantMin = o.optInt("avantMin", 2), notes = o.optString("notes"), lien = o.optString("lien"), active = o.optBoolean("active", true),
            creeMs = o.optLong("cree"), activite = o.optString("activite"), activiteSuit = o.optBoolean("activiteSuit", true),
            chaine = Chaine(profil = h.optString("profil"), poste = h.optBoolean("poste", d.poste), connecterPoste = h.optBoolean("connecterPoste", false),
                source = h.optString("source", d.source), rotor = h.optBoolean("rotor", d.rotor), connecterRotor = h.optBoolean("connecterRotor", false),
                enregistrer = h.optBoolean("enregistrer", d.enregistrer), sstv = h.optBoolean("sstv", d.sstv), annonce = h.optBoolean("annonce", d.annonce),
                parquer = h.optBoolean("parquer", false)))).getOrNull()
    }.getOrNull()

    private fun lisTrace(o: JSONObject): Trace? = runCatching {
        val im = o.optJSONArray("images") ?: JSONArray(); val fa = o.optJSONArray("faits") ?: JSONArray()
        Trace(o.getString("campagne"), o.optString("nom"), o.optInt("catnum"), o.optString("sat"), o.optLong("aos"), o.optLong("los"),
            o.optDouble("el", 0.0), Statut.valueOf(o.optString("statut", "FAIT")), o.optString("raison"), o.optLong("quand"),
            o.optString("enregistrement"), (0 until im.length()).map { im.optString(it) }, (0 until fa.length()).map { fa.optString(it) })
    }.getOrNull()

    /** The whole file: the master switch, the campaigns, the history, the pass under way (to put things back after a restart). */
    data class Fichier(val maitre: Boolean = false, val campagnes: List<Campagne> = emptyList(), val historique: List<Trace> = emptyList(),
                       val enCours: EnCoursSauve? = null)

    /** The pass under way, as kept on disk: which one, and the receive state to put back. */
    data class EnCoursSauve(val campagne: String, val aosMs: Long, val avant: Map<String, String>)

    fun ecrit(f: Fichier): JSONObject = JSONObject().put("v", 1).put("maitre", f.maitre)
        .put("campagnes", JSONArray(f.campagnes.map { json(it) }))
        .put("historique", JSONArray(f.historique.takeLast(MAX_HISTORIQUE).map { it.json() }))
        .put("enCours", f.enCours?.let { e -> JSONObject().put("campagne", e.campagne).put("aos", e.aosMs).put("avant", JSONObject(e.avant as Map<*, *>)) } ?: JSONObject.NULL)

    fun lit(texte: String?): Fichier = runCatching {
        val o = JSONObject(texte ?: "")
        val a = o.optJSONArray("campagnes") ?: JSONArray()
        val h = o.optJSONArray("historique") ?: JSONArray()
        val e = o.optJSONObject("enCours")?.let { j ->
            val av = j.optJSONObject("avant") ?: JSONObject()
            EnCoursSauve(j.optString("campagne"), j.optLong("aos"), av.keys().asSequence().associateWith { av.optString(it) })
        }
        Fichier(o.optBoolean("maitre", false),
            (0 until minOf(a.length(), MAX)).mapNotNull { a.optJSONObject(it)?.let(::lisCampagne) }.distinctBy { it.id },
            (0 until h.length()).mapNotNull { h.optJSONObject(it)?.let(::lisTrace) }.takeLast(MAX_HISTORIQUE), e)
    }.getOrDefault(Fichier())
}
