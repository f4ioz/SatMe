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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * « Agenda › Activités à venir » on the PC: the online sources
 * ([ActivitesEnLigne], kept in the data folder), the station's passes during
 * each activity, and the notices — a new activity, a useful pass coming, « ça
 * émet maintenant » — as the page's banner and toasts. « Préparer la campagne »
 * hands the page an SSTV campaign's draft for Agenda › SSTV: never saved here.
 */
class ActivitesPc(
    private val station: StationPc,
    dossier: File,
    private val horloge: () -> Long = System::currentTimeMillis,
    telecharge: ActivitesEnLigne.Telechargeur = ActivitesEnLigne.Telechargeur.okhttp(),
    /** A satellite's passes between two instants (the station's predictor). */
    private val passagesDe: (Int, Long, Long) -> List<SatPass> = station::passagesDe,
) {
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

    fun liste(): List<Activite> = enLigne.liste(sources())

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
        signale(liste())
    }

    @Synchronized private fun signale(l: List<Activite>) {
        val t = horloge()
        var m = memoire
        if (reglages.activitesNotifNouvelle) for (a in ActivitesAVenir.aSignaler(m, l, t, ::passages))
            annonce("nouvelle", "Nouvelle activité : " + resume(a), a.id)
        m = ActivitesAVenir.retiens(m, l, t)
        val emet = ActivitesAVenir.emetMaintenant(m, l, t)
        if (reglages.activitesNotifEmet) for (a in emet) annonce("emet", "Ça émet maintenant : " + a.satNom + " " + a.type.libelle + " (" + a.ecoutes + " écoute" + (if (a.ecoutes > 1) "s" else "") + " AMSAT)", a.id)
        m = m.copy(emises = m.emises + emet.map(ActivitesAVenir::cle))
        val dues = ActivitesAVenir.alertesPassages(l, ::passages, t, reglages.activitesAvantMin).filter { t >= it.quandMs && it.cle !in m.passages }
        if (reglages.activitesNotifPassage) for (d in dues) annonce("passage", d.activite.satNom + " dans " + ((d.passage.aosEpochMs - t + 59_999) / 60_000).coerceAtLeast(0) +
            " min (" + Math.round(d.passage.maxElevationDeg) + "° max) : " + d.activite.type.libelle + " — " + d.activite.titre, d.activite.id)
        m = m.copy(passages = m.passages + dues.map { it.cle })
        if (m != memoire) { memoire = m; runCatching { fichierMemoire.parentFile?.mkdirs(); fichierMemoire.writeText(ActivitesAVenir.ecrit(m).toString()) } }
    }

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
     * `/campagne?id=` an SSTV campaign's draft; POST `/reglage?k=&on=` (or
     * `&avant=`), `/rafraichit`.
     */
    fun sert(chemin: String, methode: String, p: (String) -> String): ReponseWeb {
        val route = chemin.removePrefix("/api/activites")
        if (methode != "POST") return when (route) {
            "", "/" -> json(json())
            "/campagne" -> campagne(p("id"))
            else -> refus("inconnu")
        }
        return when (route) {
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
    private fun campagne(id: String): ReponseWeb {
        val a = liste().firstOrNull { it.id == id } ?: return refus("activité introuvable")
        if (a.catnum <= 0) return refus("satellite inconnu : à choisir dans le formulaire")
        val c = ActivitesAVenir.campagne(a, horloge())
        val sat = station.etat.value.satellites.firstOrNull { it.catalogNumber == a.catnum }
        val trouve = listOfNotNull("satellite " + (sat?.name ?: a.satNom), if (c.freqHz > 0) "fréquence " + String.format(java.util.Locale.FRANCE, "%.3f", c.freqHz / 1e6) + " MHz" else null,
            if (c.modeSstv != fr.f4ioz.satcombo.domain.CampagneSstv.AUTO) "mode " + c.modeSstv else null, "période", if (c.horaire.isNotBlank()) "rythme" else null)
        return json(JSONObject().put("ok", true).put("texte", "").put("proposition", JSONObject()
            .put("nom", c.nom).put("catnum", c.catnum).put("sat", sat?.name ?: c.satNom).put("freqHz", c.freqHz).put("mode", c.modeSstv)
            .put("de", c.debutMs).put("a", c.finMs).put("aConfirmer", c.aConfirmer).put("horaire", c.horaire).put("lien", c.lien)
            .put("notes", c.notes).put("trouve", JSONArray(trouve))))
    }

    fun json(): JSONObject {
        val t = horloge()
        val s = reglages
        val l = if (s.activitesOn) liste() else emptyList()
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
            }))
            .put("annonces", JSONArray(synchronized(this) { annonces.toList() }.map { JSONObject().put("n", it.n).put("quand", it.quandMs).put("sorte", it.sorte).put("texte", it.texte).put("id", it.id) }))
    }
}
