/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.SatPass
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The one door for the voice assistant ([AssistantVocalPc]), Home Assistant
 * ([DomotiquePc]) and the owner's web site ([CommandesSitePc]): a sentence or
 * an [Intention] in, a short answer out, in French and in English.
 *
 * Questions are answered from what the tabs show ([Monde]). Actions are
 * only those of [Intention.ACTIONS_SURES] (receive and sound): a
 * transmission or a rotor move asked is answered « à faire à l'écran », and
 * nothing else is ever carried out, whoever asks.
 */
class CommandesPc(
    private val monde: Monde,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    /** What the station is, as the commands see it (Main's, or the tests' fake). */
    interface Monde {
        fun maintenant(): Long = System.currentTimeMillis()
        /** The catalogue: name, NORAD number. */
        fun satellites(): List<Pair<String, Int>>
        /** The satellites marked ★. */
        fun favoris(): Set<Int>
        /** The next passes of every satellite (each one's pass under way or next), soonest first. */
        fun passages(): List<SatPass>
        /** [catnum]'s passes between two instants (any height). */
        fun passagesDe(catnum: Int, deMs: Long, aMs: Long): List<SatPass>
        /** Where [catnum] is now: elevation (°), null unknown. */
        fun elevation(catnum: Int, t: Long): Double?
        /** The lowest elevation a pass must reach to count (Réglages). */
        fun elevationMin(): Double = 0.0
        fun autopilote(): Pilote?
        /** The last SSTV picture received on the air: satellite, time, mode. */
        fun derniereSstv(): Triple<String, Long, String>?
        fun datv(): Datv?
        fun balise(): Balise?
        fun rotor(): Rotor?
        /** The last contact of the log: callsign, satellite, time. */
        fun dernierQso(): Triple<String, String, Long>?
        /** Receiving SSTV now (the decoder listening). */
        fun sstvActif(): Boolean
        /** The receivers' sound cut here by a command. */
        fun muet(): Boolean
        // Actions: null when done, else why not (in French; [Refus] for both languages).
        fun regleAutopilote(on: Boolean): Refus?
        fun regleSstv(on: Boolean): Refus?
        fun regleDatv(on: Boolean): Refus?
        fun regleSon(muet: Boolean): Refus?
    }

    /** Why an action was not done, said in both languages. */
    data class Refus(val fr: String, val en: String)

    data class Pilote(val maitre: Boolean, val campagnes: Int, val enCours: EnCours?, val prochain: Prochain?) {
        data class EnCours(val sat: String, val campagne: String, val images: Int, val losMs: Long)
        data class Prochain(val sat: String, val campagne: String, val aosMs: Long, val elMax: Double)
    }
    data class Datv(val actif: Boolean, val verrouille: Boolean, val merDb: Double?, val cielKhz: Long, val service: String, val indicatif: String)
    /** The narrow beacon heard by the SDR ([rapportDb] over the noise, [ecartHz] off, [quandMs] measured), the wide one by the DATV receiver. */
    data class Balise(val ecoutee: Boolean, val rapportDb: Double?, val ecartHz: Long?, val quandMs: Long, val largeMerDb: Double? = null)
    data class Rotor(val connecte: Boolean, val azDeg: Double?, val elDeg: Double?, val azSeul: Boolean)

    /** An answer: [fr] and [en]; [aos] when it tells of a pass to come (a site may add « in N minutes »). */
    data class Reponse(val intention: Intention, val ok: Boolean, val fr: String, val en: String, val aos: Long? = null,
                       val satellite: Pair<String, Int>? = null) {
        fun texte(anglais: Boolean) = if (anglais) en else fr
        fun json(): JSONObject = JSONObject().put("intention", intention.id).put("ok", ok).put("fr", fr).put("en", en)
            .apply { aos?.let { put("aos", it) }; satellite?.let { put("satellite", it.first).put("catnum", it.second) } }
    }

    /** The station as the commands read it (Home Assistant's figures). */
    fun monde(): Monde = monde

    /** One command given: who, what was heard, what was answered (the page's list). */
    data class Trace(val seq: Long, val ms: Long, val source: String, val texte: String, val reponse: Reponse) {
        fun json(): JSONObject = JSONObject().put("seq", seq).put("ms", ms).put("source", source).put("texte", texte).put("reponse", reponse.json())
    }

    private val traces = ArrayDeque<Trace>()
    @Volatile var dernierSeq = 0L; private set

    /** Told of every command answered (the voice's panel, Home Assistant's « réponse »). */
    @Volatile var apres: (Trace) -> Unit = {}

    /** The commands given lately, after [seq]. */
    fun traces(seq: Long = 0L): List<Trace> = synchronized(traces) { traces.filter { it.seq > seq } }

    private fun note(source: String, texte: String, r: Reponse): Reponse {
        val t = synchronized(traces) {
            Trace(++dernierSeq, monde.maintenant(), source, texte.take(300), r).also { traces.addLast(it); while (traces.size > 30) traces.removeFirst() }
        }
        runCatching { apres(t) }
        return r
    }

    // ------------------------------------------------------------------ entry points

    /**
     * A sentence heard or typed: understood, then answered or carried out. [actions] false: questions only (an
     * action asked is answered « not allowed from here »).
     */
    fun phrase(texte: String, source: String, actions: Boolean = true): Reponse {
        val a = runCatching { Phrases.analyse(texte, monde.satellites()) }.getOrElse { Analyse(Intention.INCONNU, texte = texte) }
        return note(source, texte, repond(a.intention, a.satellite, actions))
    }

    /** An intention given by its id (Home Assistant's switches, the site's queue). An unknown id is refused. */
    fun intention(id: String, source: String, satellite: String? = null, actions: Boolean = true): Reponse {
        val i = Intention.de(id) ?: return note(source, id, inconnu())
        val sat = satellite?.takeIf { it.isNotBlank() }?.let { s -> Phrases.satellite(Phrases.normalise(s).split(' '), monde.satellites()) }
        if (satellite != null && satellite.isNotBlank() && sat == null && i == Intention.PROCHAIN_PASSAGE)
            return note(source, id, Reponse(i, false, "Je ne connais pas le satellite « $satellite ».", "I don't know the satellite \"$satellite\"."))
        return note(source, id + (sat?.let { " " + it.first } ?: ""), repond(i, sat, actions))
    }

    /** The answer, without noting it: [actions] false refuses the actions. */
    fun repond(i: Intention, sat: Pair<String, Int>? = null, actions: Boolean = true): Reponse = runCatching {
        if (i.action && !actions) return@runCatching Reponse(i, false, "Les actions ne sont pas permises d'ici.", "Actions are not allowed from here.")
        when (i) {
            Intention.EMISSION -> Reponse(i, false, EMISSION_FR, EMISSION_EN)
            Intention.INCONNU -> inconnu()
            Intention.AIDE -> Reponse(i, true, AIDE_FR, AIDE_EN)
            Intention.PROCHAIN_PASSAGE -> if (sat != null) passageDe(sat) else passageFavoris()
            Intention.PROCHAIN_AOS -> prochainAos()
            Intention.VISIBLES -> visibles()
            Intention.AUTOPILOTE_ETAT -> pilote()
            Intention.DERNIERE_SSTV -> derniereSstv()
            Intention.DATV_ETAT -> datv()
            Intention.BALISE -> balise()
            Intention.ROTOR -> rotor()
            Intention.DERNIER_QSO -> dernierQso()
            else -> if (i in Intention.ACTIONS_SURES) action(i) else inconnu()
        }
    }.getOrElse { Reponse(i, false, "Je n'ai pas pu répondre.", "I couldn't answer.") }

    private fun inconnu() = Reponse(Intention.INCONNU, false, "Je n'ai pas compris. Dites « aide » pour la liste.", "I didn't understand. Say \"help\" for the list.")

    // ------------------------------------------------------------------ actions (receive only)

    private fun action(i: Intention): Reponse {
        // Belt and braces: only the safe list, whatever called.
        if (i !in Intention.ACTIONS_SURES) return Reponse(i, false, EMISSION_FR, EMISSION_EN)
        val refus = when (i) {
            Intention.AUTOPILOTE_ON -> monde.regleAutopilote(true)
            Intention.AUTOPILOTE_OFF -> monde.regleAutopilote(false)
            Intention.SSTV_ON -> monde.regleSstv(true)
            Intention.SSTV_OFF -> monde.regleSstv(false)
            Intention.DATV_ON -> monde.regleDatv(true)
            Intention.DATV_OFF -> monde.regleDatv(false)
            Intention.SON_MUET -> monde.regleSon(true)
            Intention.SON_REMET -> monde.regleSon(false)
            else -> Refus("Action inconnue.", "Unknown action.")
        }
        if (refus != null) return Reponse(i, false, refus.fr, refus.en)
        return when (i) {
            Intention.AUTOPILOTE_ON -> {
                val p = monde.autopilote()
                if (p != null && p.campagnes == 0) Reponse(i, true, "Auto-pilote SSTV activé, mais aucune campagne n'est prévue.", "SSTV autopilot on, but no campaign is planned.")
                else Reponse(i, true, "Auto-pilote SSTV activé.", "SSTV autopilot on.")
            }
            Intention.AUTOPILOTE_OFF -> Reponse(i, true, "Auto-pilote SSTV coupé.", "SSTV autopilot off.")
            Intention.SSTV_ON -> Reponse(i, true, "Réception SSTV démarrée.", "SSTV reception started.")
            Intention.SSTV_OFF -> Reponse(i, true, "Réception SSTV arrêtée.", "SSTV reception stopped.")
            Intention.DATV_ON -> Reponse(i, true, "Réception DATV démarrée.", "DATV reception started.")
            Intention.DATV_OFF -> Reponse(i, true, "Réception DATV arrêtée.", "DATV reception stopped.")
            Intention.SON_MUET -> Reponse(i, true, "Son coupé.", "Sound muted.")
            else -> Reponse(i, true, "Son rétabli.", "Sound back on.")
        }
    }

    // ------------------------------------------------------------------ questions

    private fun passageDe(sat: Pair<String, Int>): Reponse {
        val t = monde.maintenant()
        val min = monde.elevationMin()
        val p = (monde.passages().filter { it.catalogNumber == sat.second } + monde.passagesDe(sat.second, t - 20 * 60_000L, t + 48 * 3_600_000L))
            .filter { it.losEpochMs > t && it.maxElevationDeg >= min }.minByOrNull { it.aosEpochMs }
            ?: return Reponse(Intention.PROCHAIN_PASSAGE, true, "Pas de passage de ${sat.first} dans les 48 heures.", "No ${sat.first} pass in the next 48 hours.",
                satellite = sat)
        return phrasePassage(p, sat.first, t, sat)
    }

    private fun passageFavoris(): Reponse {
        val t = monde.maintenant()
        val fav = monde.favoris()
        val l = monde.passages().filter { it.losEpochMs > t && it.maxElevationDeg >= monde.elevationMin() }
        val p = (if (fav.isEmpty()) l else l.filter { it.catalogNumber in fav }).minByOrNull { it.aosEpochMs }
            ?: return Reponse(Intention.PROCHAIN_PASSAGE, true,
                if (fav.isEmpty()) "Aucun passage prévu." else "Aucun passage prévu pour vos satellites favoris.",
                if (fav.isEmpty()) "No pass ahead." else "No pass ahead for your favourite satellites.")
        return phrasePassage(p, p.satName, t, p.satName to p.catalogNumber)
    }

    private fun phrasePassage(p: SatPass, nom: String, t: Long, sat: Pair<String, Int>?): Reponse {
        val el = Math.round(p.maxElevationDeg)
        return if (p.aosEpochMs <= t) Reponse(Intention.PROCHAIN_PASSAGE, true,
            "$nom passe en ce moment, jusqu'à ${heureFr(p.losEpochMs, t)}, $el degrés au plus haut.",
            "$nom is passing now, until ${heureEn(p.losEpochMs, t)}, $el degrees at most.", null, sat)
        else Reponse(Intention.PROCHAIN_PASSAGE, true,
            "Prochain passage de $nom ${quandFr(p.aosEpochMs, t)}, ${dansFr(p.aosEpochMs - t)}, $el degrés au plus haut.",
            "Next $nom pass ${quandEn(p.aosEpochMs, t)}, ${dansEn(p.aosEpochMs - t)}, $el degrees at most.", p.aosEpochMs, sat)
    }

    private fun prochainAos(): Reponse {
        val t = monde.maintenant()
        val p = monde.passages().filter { it.aosEpochMs > t && it.maxElevationDeg >= monde.elevationMin() }.minByOrNull { it.aosEpochMs }
            ?: return Reponse(Intention.PROCHAIN_AOS, true, "Aucun AOS prévu.", "No AOS ahead.")
        return Reponse(Intention.PROCHAIN_AOS, true,
            "Prochain AOS : ${p.satName} ${quandFr(p.aosEpochMs, t)}, ${dansFr(p.aosEpochMs - t)}.",
            "Next AOS: ${p.satName} ${quandEn(p.aosEpochMs, t)}, ${dansEn(p.aosEpochMs - t)}.", p.aosEpochMs, p.satName to p.catalogNumber)
    }

    /** The satellites above the horizon now, highest first. */
    fun visiblesMaintenant(): List<Pair<String, Double>> {
        val t = monde.maintenant()
        return monde.passages().filter { it.aosEpochMs <= t && it.losEpochMs > t }.distinctBy { it.catalogNumber }
            .mapNotNull { p -> (monde.elevation(p.catalogNumber, t) ?: return@mapNotNull null).takeIf { it > 0.0 }?.let { p.satName to it } }
            .sortedByDescending { it.second }
    }

    private fun visibles(): Reponse {
        val l = visiblesMaintenant()
        if (l.isEmpty()) {
            val p = prochainAos()
            return Reponse(Intention.VISIBLES, true, "Aucun satellite au-dessus de l'horizon. " + p.fr, "No satellite above the horizon. " + p.en, p.aos)
        }
        val n = l.size
        val noms = l.take(5)
        val fr = (if (n == 1) "Un satellite au-dessus de l'horizon : " else "$n satellites au-dessus de l'horizon : ") +
            noms.joinToString(", ") { "${it.first} à ${Math.round(it.second)} degrés" } + (if (n > 5) ", et ${n - 5} autres." else ".")
        val en = (if (n == 1) "One satellite above the horizon: " else "$n satellites above the horizon: ") +
            noms.joinToString(", ") { "${it.first} at ${Math.round(it.second)} degrees" } + (if (n > 5) ", and ${n - 5} more." else ".")
        return Reponse(Intention.VISIBLES, true, fr, en)
    }

    private fun pilote(): Reponse {
        val i = Intention.AUTOPILOTE_ETAT
        val p = monde.autopilote() ?: return Reponse(i, true, "L'auto-pilote SSTV n'est pas disponible.", "The SSTV autopilot is not available.")
        val t = monde.maintenant()
        if (!p.maitre) return Reponse(i, true, "L'auto-pilote SSTV est coupé.", "The SSTV autopilot is off.")
        p.enCours?.let { e ->
            return Reponse(i, true,
                "L'auto-pilote reçoit ${e.sat} (campagne ${e.campagne}) jusqu'à ${heureFr(e.losMs, t)} : ${images(e.images, false)} pour l'instant.",
                "The autopilot is receiving ${e.sat} (campaign ${e.campagne}) until ${heureEn(e.losMs, t)}: ${images(e.images, true)} so far.")
        }
        p.prochain?.let { x ->
            return Reponse(i, true,
                "L'auto-pilote est en marche. Prochain passage de campagne : ${x.sat} ${quandFr(x.aosMs, t)}, ${dansFr(x.aosMs - t)} (${x.campagne}).",
                "The autopilot is on. Next campaign pass: ${x.sat} ${quandEn(x.aosMs, t)}, ${dansEn(x.aosMs - t)} (${x.campagne}).", x.aosMs)
        }
        return Reponse(i, true, "L'auto-pilote est en marche, sans passage de campagne prévu.", "The autopilot is on, with no campaign pass ahead.")
    }

    private fun images(n: Int, en: Boolean) = if (en) (if (n == 1) "one picture" else "$n pictures") else (if (n == 0) "aucune image" else if (n == 1) "une image" else "$n images")

    private fun derniereSstv(): Reponse {
        val i = Intention.DERNIERE_SSTV
        val t = monde.maintenant()
        val s = monde.derniereSstv() ?: return Reponse(i, true,
            "Aucune image SSTV reçue." + if (monde.sstvActif()) " La réception SSTV est en marche." else "",
            "No SSTV picture received." + if (monde.sstvActif()) " SSTV reception is on." else "")
        val sat = s.first.ifBlank { "satellite inconnu" }
        val satEn = s.first.ifBlank { "unknown satellite" }
        val mode = s.third.takeIf { it.isNotBlank() }
        return Reponse(i, true, "Dernière image SSTV : $sat, reçue ${quandFr(s.second, t)}" + (mode?.let { " en $it" } ?: "") + ".",
            "Last SSTV picture: $satEn, received ${quandEn(s.second, t)}" + (mode?.let { " in $it" } ?: "") + ".")
    }

    private fun datv(): Reponse {
        val i = Intention.DATV_ETAT
        val d = monde.datv() ?: return Reponse(i, true, "La DATV n'est pas disponible.", "DATV is not available.")
        if (!d.actif) return Reponse(i, true, "La réception DATV est arrêtée.", "DATV reception is stopped.")
        val f = mhzFr(d.cielKhz); val fe = mhzEn(d.cielKhz)
        if (!d.verrouille) return Reponse(i, true, "La DATV cherche un signal sur $f mégahertz, sans verrouillage.", "DATV is searching on $fe megahertz, not locked.")
        val qui = d.indicatif.ifBlank { d.service }
        val mer = d.merDb?.let { String.format(Locale.FRANCE, "%.1f", it) }
        val merEn = d.merDb?.let { String.format(Locale.UK, "%.1f", it) }
        return Reponse(i, true,
            "DATV verrouillée sur $f mégahertz" + (mer?.let { ", MER $it dB" } ?: "") + (if (qui.isNotBlank()) ", station $qui" else "") + ".",
            "DATV locked on $fe megahertz" + (merEn?.let { ", MER $it dB" } ?: "") + (if (qui.isNotBlank()) ", station $qui" else "") + ".")
    }

    private fun balise(): Reponse {
        val i = Intention.BALISE
        val b = monde.balise()
        val t = monde.maintenant()
        val large = b?.largeMerDb?.let { String.format(Locale.FRANCE, "%.1f", it) to String.format(Locale.UK, "%.1f", it) }
        val etroite = b?.takeIf { it.ecoutee && it.rapportDb != null && t - it.quandMs < 120_000L }
        if (etroite == null && large == null) return Reponse(i, true, "La balise QO-100 n'est pas écoutée en ce moment.", "The QO-100 beacon is not being received right now.")
        val fr = StringBuilder(); val en = StringBuilder()
        etroite?.let {
            fr.append("Balise QO-100 reçue à ${Math.round(it.rapportDb!!)} dB au-dessus du bruit" + (it.ecartHz?.let { e -> ", écart $e hertz" } ?: "") + ".")
            en.append("QO-100 beacon received ${Math.round(it.rapportDb)} dB above the noise" + (it.ecartHz?.let { e -> ", offset $e hertz" } ?: "") + ".")
        }
        large?.let { (f, e) ->
            if (fr.isNotEmpty()) { fr.append(' '); en.append(' ') }
            fr.append("Balise large bande : MER $f dB."); en.append("Wide band beacon: MER $e dB.")
        }
        return Reponse(i, true, fr.toString(), en.toString())
    }

    private fun rotor(): Reponse {
        val i = Intention.ROTOR
        val r = monde.rotor()?.takeIf { it.connecte && it.azDeg != null }
            ?: return Reponse(i, true, "Le rotor n'est pas connecté.", "The rotator is not connected.")
        val az = Math.round(r.azDeg!!)
        val el = r.elDeg?.takeIf { !r.azSeul }?.let { Math.round(it) }
        return Reponse(i, true, "L'antenne pointe à $az degrés d'azimut" + (el?.let { " et $it degrés d'élévation" } ?: "") + ".",
            "The antenna points at $az degrees azimuth" + (el?.let { " and $it degrees elevation" } ?: "") + ".")
    }

    private fun dernierQso(): Reponse {
        val i = Intention.DERNIER_QSO
        val t = monde.maintenant()
        val q = monde.dernierQso() ?: return Reponse(i, true, "Aucun QSO dans le carnet.", "No QSO in the log.")
        val sat = q.second.takeIf { it.isNotBlank() }
        return Reponse(i, true, "Dernier QSO : ${epele(q.first)}" + (sat?.let { " sur $it" } ?: "") + ", ${quandFr(q.third, t)}.",
            "Last QSO: ${epele(q.first)}" + (sat?.let { " on $it" } ?: "") + ", ${quandEn(q.third, t)}.")
    }

    /** A callsign as written (the voices spell capitals and digits well enough). */
    private fun epele(indicatif: String) = indicatif.uppercase()

    // ------------------------------------------------------------------ the whole state (site, Home Assistant)

    /** Every question's answer now, by intention id (the site answers from them; [aos] kept). */
    fun reponses(): JSONObject {
        val o = JSONObject()
        Intention.QUESTIONS.forEach { i -> o.put(i.id, repond(i).json().apply { remove("intention"); remove("ok") }) }
        return o
    }

    /**
     * The next pass of the satellites asked about most: the favourites, the one followed, those named in [autres],
     * then the passes ahead, [max] at most; each with its keys ([Phrases.clesDe]) for the site to match a name said.
     */
    fun passagesConnus(autres: Set<Int> = emptySet(), max: Int = 40): JSONArray {
        val t = monde.maintenant()
        val noms = monde.satellites().associate { it.second to it.first }
        val voulus = LinkedHashSet<Int>().apply { addAll(monde.favoris()); addAll(autres); addAll(COURANTS) }
        val a = JSONArray()
        val faits = HashSet<Int>()
        fun ajoute(catnum: Int, nom: String, p: SatPass?) {
            if (a.length() >= max || !faits.add(catnum)) return
            val r = if (p == null) null else phrasePassage(p, nom, t, nom to catnum)
            a.put(JSONObject().put("nom", nom).put("catnum", catnum).put("cles", JSONArray(Phrases.clesDe(nom, catnum).toList()))
                .apply {
                    if (p != null && r != null) put("aos", p.aosEpochMs).put("los", p.losEpochMs).put("el", Math.round(p.maxElevationDeg)).put("fr", r.fr).put("en", r.en)
                    else put("fr", "Pas de passage de $nom dans les 48 heures.").put("en", "No $nom pass in the next 48 hours.")
                })
        }
        val prochains = monde.passages().filter { it.losEpochMs > t && it.maxElevationDeg >= monde.elevationMin() }.sortedBy { it.aosEpochMs }
        voulus.forEach { n ->
            val nom = noms[n] ?: return@forEach
            val p = prochains.firstOrNull { it.catalogNumber == n }
                ?: runCatching { monde.passagesDe(n, t - 20 * 60_000L, t + 48 * 3_600_000L) }.getOrDefault(emptyList())
                    .filter { it.losEpochMs > t && it.maxElevationDeg >= monde.elevationMin() }.minByOrNull { it.aosEpochMs }
            ajoute(n, nom, p)
        }
        prochains.forEach { ajoute(it.catalogNumber, it.satName, it) }
        return a
    }

    // ------------------------------------------------------------------ words for times

    private fun local(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone())

    internal fun heureFr(ms: Long, t: Long): String { val z = local(ms); return "${z.hour} h ${"%02d".format(z.minute)}".let { if (jour(ms, t) == 0L) it else jourFr(ms, t) + " à " + it } }
    internal fun heureEn(ms: Long, t: Long): String { val z = local(ms); return "%02d:%02d".format(z.hour, z.minute).let { if (jour(ms, t) == 0L) it else jourEn(ms, t) + " at " + it } }

    /** « à 14 h 32 », « demain à 9 h 05 », « le 30 octobre à 9 h 05 », « hier à … ». */
    internal fun quandFr(ms: Long, t: Long): String { val z = local(ms); val h = "${z.hour} h ${"%02d".format(z.minute)}"; return if (jour(ms, t) == 0L) "à $h" else jourFr(ms, t) + " à " + h }
    internal fun quandEn(ms: Long, t: Long): String { val z = local(ms); val h = "%02d:%02d".format(z.hour, z.minute); return if (jour(ms, t) == 0L) "at $h" else jourEn(ms, t) + " at " + h }

    private fun jour(ms: Long, t: Long): Long = java.time.temporal.ChronoUnit.DAYS.between(local(t).toLocalDate(), local(ms).toLocalDate())
    private fun jourFr(ms: Long, t: Long) = when (jour(ms, t)) { 1L -> "demain"; -1L -> "hier"; else -> "le " + local(ms).format(DateTimeFormatter.ofPattern("d MMMM", Locale.FRENCH)) }
    private fun jourEn(ms: Long, t: Long) = when (jour(ms, t)) { 1L -> "tomorrow"; -1L -> "yesterday"; else -> "on " + local(ms).format(DateTimeFormatter.ofPattern("d MMMM", Locale.UK)) }

    companion object {
        const val EMISSION_FR = "Pour la sécurité, l'émission et les mouvements d'antenne se commandent seulement à l'écran de la station."
        const val EMISSION_EN = "For safety, transmitting and moving the antenna can only be done from the station's screen."
        const val AIDE_FR = "Je peux dire le prochain passage d'un satellite ou de vos favoris, les satellites visibles, le prochain AOS, l'état de l'auto-pilote, " +
            "la dernière image SSTV, l'état de la DATV et de la balise, la position du rotor et le dernier QSO. Je peux aussi activer ou couper l'auto-pilote, " +
            "la réception SSTV ou DATV, et couper le son. Jamais d'émission."
        const val AIDE_EN = "I can tell the next pass of a satellite or of your favourites, the satellites in view, the next AOS, the autopilot's state, " +
            "the last SSTV picture, the DATV and beacon state, the rotator's position and the last QSO. I can also switch the autopilot, " +
            "SSTV or DATV reception on or off, and mute the sound. Never any transmission."

        /** Satellites often asked about, whatever the favourites (ISS, SO-50, AO-91, RS-44, AO-7, FO-29, PO-101, IO-117, AO-27, QO-100). */
        val COURANTS = listOf(25544, 27607, 43017, 44909, 7530, 24278, 43678, 53106, 22825, 43700)

        /** « dans 25 minutes », « dans 2 h 10 », « dans 3 jours ». */
        fun dansFr(ms: Long): String {
            val m = Math.max(0L, (ms + 30_000L) / 60_000L)
            return when {
                m < 1 -> "dans moins d'une minute"
                m == 1L -> "dans une minute"
                m < 90 -> "dans $m minutes"
                m < 48 * 60 -> "dans ${m / 60} h ${"%02d".format(m % 60)}"
                else -> "dans ${m / 1440} jours"
            }
        }
        fun dansEn(ms: Long): String {
            val m = Math.max(0L, (ms + 30_000L) / 60_000L)
            return when {
                m < 1 -> "in less than a minute"
                m == 1L -> "in one minute"
                m < 90 -> "in $m minutes"
                m < 48 * 60 -> "in ${m / 60} h ${"%02d".format(m % 60)}"
                else -> "in ${m / 1440} days"
            }
        }
        private fun mhzFr(khz: Long) = String.format(Locale.FRANCE, "%.3f", khz / 1000.0).trimEnd('0').trimEnd(',')
        private fun mhzEn(khz: Long) = String.format(Locale.UK, "%.3f", khz / 1000.0).trimEnd('0').trimEnd('.')
    }
}
