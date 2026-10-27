/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.ActivitesEnLigne
import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.demo.ReponseWeb
import fr.f4ioz.satcombo.domain.ActivitesAVenir
import fr.f4ioz.satcombo.domain.ActivitesAVenir.Activite
import fr.f4ioz.satcombo.domain.CampagneSstv
import fr.f4ioz.satcombo.domain.NomsSatellites
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * « Agenda › Activités à venir » on the PC: the online sources
 * ([ActivitesEnLigne], kept in the data folder), the station's passes during
 * each activity, and the notices — a new activity, a useful pass coming, « ça
 * émet maintenant » — as the page's banner and toasts. « Préparer la campagne »
 * hands the page an SSTV campaign's draft for Agenda › SSTV: never saved here;
 * « Activer l'auto-pilote » makes, keeps and switches on that campaign in one
 * click ([AutoPiloteSstvPc.activeActivite]), receive only. A satellite the
 * post names without its number is looked up in the catalogue
 * ([NomsSatellites]), or as the owner picked it once (kept by name).
 */
class ActivitesPc(
    private val station: StationPc,
    dossier: File,
    private val horloge: () -> Long = System::currentTimeMillis,
    telecharge: ActivitesEnLigne.Telechargeur = ActivitesEnLigne.Telechargeur.okhttp(),
    /** A satellite's passes between two instants (the station's predictor). */
    private val passagesDe: (Int, Long, Long) -> List<SatPass> = station::passagesDe,
    /** The demonstration's made-up activities at an instant (none otherwise). */
    private val fictives: (Long) -> List<Activite> = { emptyList() },
    /** The catalogue's satellites (name, NORAD number). */
    private val catalogue: () -> List<Pair<String, Int>> = { station.etat.value.satellites.map { it.name to it.catalogNumber } },
) {
    /** Set by Main: the SSTV auto-pilote the one-click campaigns go to (absent in some tests). */
    @Volatile var autopilote: AutoPiloteSstvPc? = null

    val enLigne = ActivitesEnLigne(dossier, horloge, telecharge)
    private val fichierMemoire = File(dossier, "activites-alertes.json")
    @Volatile private var memoire = runCatching { ActivitesAVenir.litMemoire(fichierMemoire.readText()) }.getOrDefault(ActivitesAVenir.Memoire())

    /** A notice for the page: numbered, the page shows those it has not seen. */
    data class Annonce(val n: Long, val quandMs: Long, val sorte: String, val texte: String, val id: String)
    private val annonces = ArrayList<Annonce>()
    private var numero = horloge()

    private val reglages get() = station.settings
    fun sources(): ActivitesEnLigne.Reglages = reglages.reglagesActivites

    // ------------------------------------------------------------------ passes

    private val cache = HashMap<String, Pair<Long, List<SatPass>>>()

    /** [a]'s passes worth listening to (worked out again every 10 minutes). */
    fun passages(a: Activite): List<SatPass> {
        val t = horloge()
        val (de, jusqua) = ActivitesAVenir.fenetrePassages(a, t) ?: return emptyList()
        val cle = a.catnum.toString() + ":" + a.debutMs + ":" + a.finMs
        synchronized(cache) { cache[cle]?.let { (quand, l) -> if (t - quand < 10 * 60_000L) return ActivitesAVenir.passages(a, l, elMin(), t) } }
        val l = runCatching { passagesDe(a.catnum, de - 30 * 60_000L, jusqua) }.getOrDefault(emptyList())
        synchronized(cache) { if (cache.size > 60) cache.clear(); cache[cle] = t to l }
        return ActivitesAVenir.passages(a, l, elMin(), t)
    }

    private fun elMin() = reglages.minElevDeg.toDouble()

    /** A one-click campaign's elevation: the one asked ([p], the dialog's), else the station's but at least 10°. */
    private fun elMinAp(p: String) = p.replace(',', '.').trim().toDoubleOrNull()?.takeIf { !it.isNaN() }?.coerceIn(0.0, 90.0)
        ?: CampagneSstv.elMinActivite(elMin(), reglages.rotorMinEl.toDouble())

    /** The activities as the sources say them (and the demonstration's). */
    fun listeBrute(): List<Activite> {
        val r = sources()
        val l = enLigne.liste(r)
        return if (!r.actif) l else (l + fictives(horloge())).sortedBy { it.debutMs }
    }

    /** The same, each satellite named without its number found in the catalogue (or as the owner picked it). */
    fun liste(): List<Activite> {
        val l = listeBrute()
        if (l.none { it.catnum <= 0 }) return l
        val cat = catalogue()
        val choix = choix
        return l.map { a -> if (a.catnum > 0) a else (choix["id:" + a.id] ?: choix["nom:" + NomsSatellites.cle(a.satNom)].takeIf { a.satNom.isNotBlank() }
            ?: NomsSatellites.identifie(a.satNom, a.titre, cat)).let { n -> if (n != null && n > 0) a.copy(catnum = n) else a } }
    }

    // ------------------------------------------------------------------ satellites picked by the owner

    private val fichierChoix = File(dossier, "activites-satellites.json")
    /** « nom:UMKA1 » or « id:… » → NORAD number, as the owner picked it in « Activer l'auto-pilote ». */
    @Volatile private var choix: Map<String, Int> = runCatching {
        val o = JSONObject(fichierChoix.readText()); o.keys().asSequence().associateWith { o.optInt(it) }.filterValues { it > 0 }
    }.getOrDefault(emptyMap())

    @Synchronized private fun retiensChoix(a: Activite, catnum: Int) {
        val k = if (a.satNom.isNotBlank() && NomsSatellites.cle(a.satNom).isNotEmpty()) "nom:" + NomsSatellites.cle(a.satNom) else "id:" + a.id
        if (choix[k] == catnum) return
        choix = (choix + (k to catnum)).entries.toList().takeLast(200).associate { it.key to it.value }
        runCatching { fichierChoix.parentFile?.mkdirs(); fichierChoix.writeText(JSONObject(choix as Map<*, *>).toString()) }
    }

    // ------------------------------------------------------------------ running

    @Volatile private var forcer = false
    /** The owner asked for the sources now (still at most every 5 min each). */
    val rafraichissementDemande: Boolean get() = forcer
    fun demandeRafraichissement() { forcer = true }

    /** Every minute (off the page's thread): the sources due fetched, then what to signal. */
    fun tic() {
        val r = sources()
        if (!r.actif) return
        val f = forcer; forcer = false
        runCatching { enLigne.rafraichit(r, station.etat.value.satellites.map { it.name to it.catalogNumber }, f) }
        val l = liste()
        signale(listeBrute(), l)
        // The campaigns made from an activity follow its new dates (unless changed by hand).
        runCatching { autopilote?.suitActivites(l) }
    }

    /** What was told is remembered by the activities as the sources say them ([l]); the passes are those of [nommees]. */
    @Synchronized private fun signale(l: List<Activite>, nommees: List<Activite>) {
        val t = horloge()
        var m = memoire
        if (reglages.activitesNotifNouvelle) for (a in ActivitesAVenir.aSignaler(m, l, t, ::passages))
            annonce("nouvelle", "Nouvelle activité : " + resume(a), a.id)
        m = ActivitesAVenir.retiens(m, l, t)
        val emet = ActivitesAVenir.emetMaintenant(m, l, t)
        if (reglages.activitesNotifEmet) for (a in emet) annonce("emet", "Ça émet maintenant : " + a.satNom + " " + a.type.libelle + " (" + a.ecoutes + " écoute" + (if (a.ecoutes > 1) "s" else "") + " AMSAT)", a.id)
        m = m.copy(emises = m.emises + emet.map(ActivitesAVenir::cle))
        val dues = ActivitesAVenir.alertesPassages(nommees, ::passages, t, reglages.activitesAvantMin).filter { t >= it.quandMs && it.cle !in m.passages }
        if (reglages.activitesNotifPassage) for (d in dues) annonce("passage", d.activite.satNom + " dans " + ((d.passage.aosEpochMs - t + 59_999) / 60_000).coerceAtLeast(0) +
            " min (" + Math.round(d.passage.maxElevationDeg) + "° max) : " + d.activite.type.libelle + " — " + d.activite.titre, d.activite.id)
        m = m.copy(passages = m.passages + dues.map { it.cle })
        if (m != memoire) { memoire = m; runCatching { fichierMemoire.parentFile?.mkdirs(); fichierMemoire.writeText(ActivitesAVenir.ecrit(m).toString()) } }
    }

    /** A notice from elsewhere (« diapo manquante attendue »), shown as the activities' own. */
    @Synchronized fun signale(sorte: String, texte: String, id: String) = annonce(sorte, texte, id)

    private fun annonce(sorte: String, texte: String, id: String) {
        annonces += Annonce(++numero, horloge(), sorte, texte, id)
        while (annonces.size > 30) annonces.removeAt(0)
        println("activités : $texte")
    }

    private fun resume(a: Activite): String = a.satNom.ifBlank { "?" } + " " + a.type.libelle + " — " + a.titre +
        (if (a.freqHz > 0) " · " + String.format(java.util.Locale.FRANCE, "%.3f", a.freqHz / 1e6) + " MHz" else "") + (if (a.mode.isNotBlank()) " " + a.mode else "")

    // ------------------------------------------------------------------ the page

    private fun json(o: Any) = ReponseWeb("200 OK", "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))
    private fun refus(t: String) = json(JSONObject().put("ok", false).put("erreur", t))

    /**
     * `/api/activites`: GET the list, the sources' state, the notices; GET
     * `/campagne?id=` an SSTV campaign's draft, `/autopilote?id=&catnum=&el=`
     * what « Activer l'auto-pilote » would do; POST `/autopilote?id=&catnum=&el=`
     * does it, `/reglage?k=&on=` (or `&avant=`), `/rafraichit`.
     */
    fun sert(chemin: String, methode: String, p: (String) -> String): ReponseWeb {
        val route = chemin.removePrefix("/api/activites")
        if (methode != "POST") return when (route) {
            "", "/" -> json(json())
            "/campagne" -> campagne(p("id"), p("catnum").toIntOrNull() ?: 0)
            "/autopilote" -> apercu(p("id"), p("catnum").toIntOrNull() ?: 0, elMinAp(p("el")))
            else -> refus("inconnu")
        }
        return when (route) {
            "/autopilote" -> activeAutopilote(p("id"), p("catnum").toIntOrNull() ?: 0, elMinAp(p("el")))
            "/reglage" -> {
                val on = p("on") == "1"
                val s = reglages
                when (p("k")) {
                    "actif" -> s.activitesOn = on; "ariss" -> s.activitesAriss = on; "amsat" -> s.activitesAmsat = on
                    "satnogs" -> s.activitesSatnogs = on; "rss" -> s.activitesRss = on
                    "nouvelle" -> s.activitesNotifNouvelle = on; "passage" -> s.activitesNotifPassage = on; "emet" -> s.activitesNotifEmet = on
                    "avant" -> s.activitesAvantMin = p("avant").toIntOrNull() ?: 10
                    else -> return refus("réglage inconnu")
                }
                if (on) demandeRafraichissement()
                json(JSONObject().put("ok", true))
            }
            "/rafraichit" -> { demandeRafraichissement(); json(JSONObject().put("ok", true)) }
            else -> refus("inconnu")
        }
    }

    /** The form Agenda › SSTV fills (as a flyer's): the owner checks it and saves it. */
    private fun campagne(id: String, catnum: Int = 0): ReponseWeb {
        val a = liste().firstOrNull { it.id == id }?.let { if (catnum > 0) it.copy(catnum = catnum) else it } ?: return refus("activité introuvable")
        // A satellite not found: the form says so, the owner picks it there.
        val c = ActivitesAVenir.campagne(a, horloge())
        val sat = station.etat.value.satellites.firstOrNull { it.catalogNumber == a.catnum }
        val trouve = listOfNotNull(if (a.catnum > 0) "satellite " + (sat?.name ?: a.satNom) else null, if (c.freqHz > 0) "fréquence " + String.format(java.util.Locale.FRANCE, "%.3f", c.freqHz / 1e6) + " MHz" else null,
            if (c.modeSstv != fr.f4ioz.satcombo.domain.CampagneSstv.AUTO) "mode " + c.modeSstv else null, "période", if (c.horaire.isNotBlank()) "rythme" else null)
        return json(JSONObject().put("ok", true).put("texte", "").put("proposition", JSONObject()
            .put("nom", c.nom).put("catnum", c.catnum).put("sat", sat?.name ?: c.satNom).put("freqHz", c.freqHz).put("mode", c.modeSstv)
            .put("de", c.debutMs).put("a", c.finMs).put("aConfirmer", c.aConfirmer).put("horaire", c.horaire).put("lien", c.lien)
            .put("notes", c.notes).put("trouve", JSONArray(trouve))))
    }

    /** The satellite to use for [a]: the one chosen ([catnum] > 0), else the one found. */
    private fun satDe(a: Activite, catnum: Int) = if (catnum > 0) catnum else a.catnum

    private fun lien(a: Activite, ap: AutoPiloteSstvPc): JSONObject? = ap.campagneDe(a)?.let { c ->
        val plan = runCatching { ap.plan() }.getOrDefault(emptyList()).filter { it.campagne.id == c.id }
        JSONObject().put("id", c.id).put("nom", c.nom).put("active", c.active).put("maitre", ap.maitre).put("suit", c.activiteSuit)
            .put("prochain", plan.firstOrNull { it.statut == CampagneSstv.Statut.PREVU }?.passage?.aosEpochMs ?: JSONObject.NULL)
            .put("enCours", plan.any { it.statut == CampagneSstv.Statut.EN_COURS })
    }

    /** What « Activer l'auto-pilote » on activity [id] would make (satellite [catnum] when the owner chose one): shown before. */
    private fun apercu(id: String, catnum: Int, el: Double): ReponseWeb {
        val ap = autopilote ?: return refus("auto-pilote absent")
        val a0 = liste().firstOrNull { it.id == id } ?: return refus("activité introuvable")
        if (a0.type != ActivitesAVenir.Type.SSTV) return refus("seules les activités SSTV ont un auto-pilote")
        val n = satDe(a0, catnum)
        val a = a0.copy(catnum = n)
        val cat = catalogue()
        val o = JSONObject().put("ok", true).put("id", a.id).put("titre", a.titre).put("satNom", a.satNom).put("catnum", n)
            .put("satCatalogue", cat.firstOrNull { it.second == n }?.first ?: JSONObject.NULL)
            .put("trouve", a0.catnum > 0 && catnum <= 0)
            .put("candidats", JSONArray(NomsSatellites.candidats(a.satNom, a.titre, cat).map { JSONObject().put("nom", it.first).put("catnum", it.second) }))
            .put("precision", a.precision.name).put("datesInconnues", a.precision == ActivitesAVenir.Precision.INCONNUE)
            .put("freqInconnue", a.freqHz <= 0).put("maitre", ap.maitre).put("elMin", el).put("elMinDefaut", CampagneSstv.elMinActivite(elMin(), reglages.rotorMinEl.toDouble()))
        if (n <= 0) return json(o.put("campagne", JSONObject.NULL).put("erreur", "satellite à choisir"))
        o.put("existante", lien(a, ap) ?: JSONObject.NULL)
        ap.depuisActivite(a, n, el).fold({ c ->
            // The passes the campaign will receive: in its period, at least its elevation.
            o.put("passages", JSONArray(passages(a).filter { it.maxElevationDeg >= c.elMin && it.losEpochMs > c.debutMs && it.aosEpochMs < c.finMs }.take(12)
                .map { p -> JSONObject().put("aos", p.aosEpochMs).put("los", p.losEpochMs).put("el", Math.round(p.maxElevationDeg)) }))
            val hz = if (c.freqHz > 0) c.freqHz else station.emetteursDe(n).firstOrNull { it.description == c.emetteur }?.downlinkLowHz ?: 0L
            o.put("campagne", CampagneSstv.json(c).put("freqEmetteur", hz))
        }, { o.put("campagne", JSONObject.NULL).put("erreur", it.message ?: "refusé") })
        return json(o)
    }

    /** « Activer l'auto-pilote »: the campaign made (or found), kept, switched on, the master switch on. */
    private fun activeAutopilote(id: String, catnum: Int, el: Double): ReponseWeb {
        val ap = autopilote ?: return refus("auto-pilote absent")
        val a0 = liste().firstOrNull { it.id == id } ?: return refus("activité introuvable")
        if (a0.type != ActivitesAVenir.Type.SSTV) return refus("seules les activités SSTV ont un auto-pilote")
        val n = satDe(a0, catnum)
        if (n <= 0 || catalogue().none { it.second == n }) return refus("choisir le satellite")
        val r = ap.activeActivite(a0.copy(catnum = n), n, el).getOrElse { return refus(it.message ?: "refusé") }
        if (n != a0.catnum) retiensChoix(a0, n)
        return json(JSONObject().put("ok", true).put("campagne", CampagneSstv.json(r.campagne)).put("nouvelle", r.nouvelle)
            .put("maitreAllume", r.maitreAllume).put("lien", lien(a0.copy(catnum = n), ap) ?: JSONObject.NULL))
    }

    fun json(): JSONObject {
        val t = horloge()
        val s = reglages
        val l = if (s.activitesOn) liste() else emptyList()
        val ap = autopilote
        val cat = if (l.any { it.catnum > 0 }) catalogue() else emptyList()
        return JSONObject().put("ok", true).put("maintenant", t)
            .put("reglages", JSONObject().put("actif", s.activitesOn).put("ariss", s.activitesAriss).put("amsat", s.activitesAmsat)
                .put("satnogs", s.activitesSatnogs).put("rss", s.activitesRss).put("nouvelle", s.activitesNotifNouvelle)
                .put("passage", s.activitesNotifPassage).put("emet", s.activitesNotifEmet).put("avant", s.activitesAvantMin).put("elMin", s.minElevDeg))
            .put("sources", JSONArray(enLigne.etats().map { e ->
                JSONObject().put("id", e.src.name).put("nom", e.src.nom).put("lien", e.src.lien).put("tentative", e.tentativeMs)
                    .put("succes", e.succesMs).put("erreur", e.erreur).put("actif", sources().permet(e.src))
            }))
            .put("activites", JSONArray(l.map { a ->
                ActivitesAVenir.json(a).put("libelle", a.type.libelle).put("confianceLibelle", a.confiance.libelle)
                    .put("passages", JSONArray(passages(a).take(12).map { p -> JSONObject().put("aos", p.aosEpochMs).put("los", p.losEpochMs).put("el", Math.round(p.maxElevationDeg)) }))
                    .put("campagne", a.type == ActivitesAVenir.Type.SSTV && a.catnum > 0)
                    // « Activer l'auto-pilote »: every SSTV activity (its satellite picked when not found), and its campaign once made.
                    .put("autopilote", a.type == ActivitesAVenir.Type.SSTV && ap != null)
                    .put("satCatalogue", if (a.catnum > 0) cat.firstOrNull { it.second == a.catnum }?.first ?: JSONObject.NULL else JSONObject.NULL)
                    .put("lienAp", ap?.let { lien(a, it) } ?: JSONObject.NULL)
            }))
            .put("annonces", JSONArray(synchronized(this) { annonces.toList() }.map { JSONObject().put("n", it.n).put("quand", it.quandMs).put("sorte", it.sorte).put("texte", it.texte).put("id", it.id) }))
    }
}
