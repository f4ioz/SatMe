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
import fr.f4ioz.satcombo.data.DiplomesEnLigne
import fr.f4ioz.satcombo.data.LogEntry
import fr.f4ioz.satcombo.demo.ReponseWeb
import fr.f4ioz.satcombo.domain.ActivitesAVenir
import fr.f4ioz.satcombo.domain.CampagneSstv
import fr.f4ioz.satcombo.domain.Diplomes
import fr.f4ioz.satcombo.domain.Diplomes.Diplome
import org.json.JSONArray
import org.json.JSONObject
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.util.TimeZone
import javax.imageio.ImageIO

/**
 * « Agenda › Diplômes » on the PC: the awards this station can claim — the
 * standing ones ([Diplomes.CATALOGUE]), those announced online
 * ([DiplomesEnLigne]), the ARISS sessions of the calendar and the campaigns,
 * those the owner added from a flyer or a pasted text — matched with the
 * gallery's pictures and the log; the notices (newly obtainable, a deadline
 * near, a new award announced) as the page's toasts and badge.
 *
 * « Préparer la demande » gathers what the issuer's form asks, to copy: SatMe
 * never fills nor sends a form (that would need the owner's explicit
 * consent, later). Kept in `diplomes.json` (and the certificates received in
 * `diplomes/`).
 */
class DiplomesPc(
    private val station: StationPc,
    private val dossier: File,
    private val horloge: () -> Long = System::currentTimeMillis,
    telecharge: ActivitesEnLigne.Telechargeur = ActivitesEnLigne.Telechargeur.okhttp(),
    /** The gallery's pictures, as the matching needs them. */
    private val images: () -> List<Diplomes.Image> = { emptyList() },
    /** The log. */
    private val contacts: () -> List<LogEntry> = { emptyList() },
    /** Each contact's QSL flags read from the online log (by its time), for the confirmed counts by source. */
    private val confirmations: () -> Map<Long, fr.f4ioz.satcombo.data.StatutsQsl.Statut> = { emptyMap() },
    /** The activities announced (the ARISS sessions). */
    private val activites: () -> List<ActivitesAVenir.Activite> = { emptyList() },
    /** A picture's date, frequency, pass… for the request (the SSTV options). */
    private val infosImage: (String) -> JSONObject? = { null },
    /** The text read in a flyer (tesseract). */
    private val ocr: (BufferedImage) -> String? = ::lisAffiche,
    /** The demonstration's made-up awards at an instant (none otherwise). */
    private val fictifs: (Long) -> List<Diplome> = { emptyList() },
    /** False in the demonstration: its made-up awards only, nothing read online. */
    private val enLigneAutorise: Boolean = true,
) {
    val enLigne = DiplomesEnLigne(dossier, horloge, telecharge)
    private val fichier = File(dossier, "diplomes.json")
    private val dossierCertificats = File(dossier, "diplomes")
    @Volatile private var donnees: Diplomes.Fichier = runCatching { Diplomes.lisFichier(fichier.readText()) }.getOrDefault(Diplomes.Fichier())

    /** A notice for the page: numbered, the page shows those it has not seen. */
    data class Annonce(val n: Long, val quandMs: Long, val sorte: String, val texte: String, val id: String)
    private val annonces = ArrayList<Annonce>()
    private var numero = horloge()

    @Synchronized private fun garde(f: Diplomes.Fichier) {
        if (f == donnees) return
        donnees = f
        runCatching {
            fichier.parentFile?.mkdirs()
            val tmp = File(fichier.parentFile, fichier.name + ".tmp")
            tmp.writeText(Diplomes.ecrit(f).toString(2))
            if (!tmp.renameTo(fichier)) { fichier.delete(); tmp.renameTo(fichier) }
        }
    }

    // ------------------------------------------------------------------ the list

    private fun campagnes(): List<CampagneSstv.Campagne> =
        runCatching { CampagneSstv.lit(File(dossier, "sstv-campagnes.json").readText()).campagnes }.getOrDefault(emptyList())

    private fun estIss(catnum: Int, nom: String) = catnum == ActivitesAVenir.ISS || Regex("^ISS\\b", RegexOption.IGNORE_CASE).containsMatchIn(nom.trim())

    /**
     * Every award known now: the catalogue, the online ones, those kept or
     * added, an award for each ARISS session the calendar or a campaign
     * knows; each ISS event widened by the SSTV periods that overlap it.
     */
    fun tous(): List<Diplome> {
        val t = horloge()
        val acts = runCatching { activites() }.getOrDefault(emptyList()).filter { it.type == ActivitesAVenir.Type.SSTV && estIss(it.catnum, it.satNom) &&
            it.precision != ActivitesAVenir.Precision.INCONNUE }
        val camps = campagnes().filter { estIss(it.catnum, it.satNom) && it.debutMs > 0 }
        val periodes = acts.map { it.debutMs..it.finMs } + camps.map { it.debutMs..it.finMs }
        // A session of ARISS's (its name says so): its award, when its page did not give it.
        val sessions = acts.filter { a -> Regex("\\bARISS\\b", RegexOption.IGNORE_CASE).containsMatchIn(a.titre + " " + a.sources.joinToString { it.nom }) }
            .map { Diplomes.depuisSessionAriss(it.titre, it.debutMs, it.finMs, it.freqHz, it.mode) } +
            camps.filter { Regex("\\bARISS\\b", RegexOption.IGNORE_CASE).containsMatchIn(it.nom) }
                .map { Diplomes.depuisSessionAriss(it.nom, it.debutMs, it.finMs, it.freqHz, it.modeSstv.takeIf { m -> m != CampagneSstv.AUTO }.orEmpty()) }
        val f = donnees
        val enLigneL = if (f.reglages.sources && enLigneAutorise) enLigne.liste() else emptyList()
        // What the sources say wins over what was kept of it; kept: what they no longer say.
        val decouverts = Diplomes.fusionne(enLigneL + sessions + f.connus.filter { c -> enLigneL.none { it.id == c.id } } + fictifs(t))
        return Diplomes.CATALOGUE + decouverts.map { d ->
            if (d.evenement && d.satNom.isNotBlank() && Diplomes.memeSatellite(d, "ISS")) Diplomes.etend(d, periodes) else d
        }
    }

    /** The awards found (not the catalogue, not the demonstration's) kept, so a source forgetting one forgets nothing here. */
    private fun retiens(l: List<Diplome>) {
        val t = horloge()
        val f = donnees
        val nouveaux = l.filter { !it.id.startsWith("cat:") && !it.fictif }
        val gardes = (f.connus.filter { c -> nouveaux.none { it.id == c.id } } + nouveaux)
            .filter { d -> d.source == "Affiche" || d.source == "Texte" || f.suivis.containsKey(d.id) || d.finMs == 0L || d.finMs > t - 365 * Diplomes.JOUR }
            .takeLast(300)
        if (gardes != f.connus) garde(f.copy(connus = gardes))
    }

    private fun tz(): TimeZone = TimeZone.getDefault()

    fun bilans(): List<Diplomes.Bilan> {
        val t = horloge()
        val imgs = runCatching { images() }.getOrDefault(emptyList())
        val log = runCatching { contacts() }.getOrDefault(emptyList())
        val carre = station.etat.value.locator
        val f = donnees
        val conf = runCatching { confirmations() }.getOrDefault(emptyMap())
        // The grids awards: how many are confirmed, and by what (LoTW + paper for the ARRL, eQSL apart).
        val vucc = if (conf.isEmpty()) "" else fr.f4ioz.satcombo.domain.Confirmations.remarqueVucc(log, conf)
        return tous().map { Diplomes.bilan(it, imgs, log, carre, f.suivis[it.id], t, tz()) }.map { b ->
            if (vucc.isEmpty() || b.diplome.genre != Diplomes.Genre.GRILLES) b
            else b.copy(remarque = listOf(b.remarque, vucc).filter { it.isNotBlank() }.joinToString(" · "))
        }
    }

    // ------------------------------------------------------------------ running

    @Volatile private var forcer = false
    val rafraichissementDemande: Boolean get() = forcer

    /** Every minute (off the page's thread): the sources due fetched, then what to tell. */
    fun tic() {
        val r = donnees.reglages
        if (!r.actif) return
        val f = forcer; forcer = false
        if (r.sources && enLigneAutorise) runCatching { enLigne.rafraichit(station.etat.value.satellites.map { it.name to it.catalogNumber }, f) }
        retiens(tous())
        signale(bilans())
    }

    @Synchronized private fun signale(bilans: List<Diplomes.Bilan>) {
        val f = donnees
        val r = f.reglages
        val avis = Diplomes.aSignaler(bilans, f.dits, horloge(), r.jours, r.obtenable, r.echeance, r.nouveau, tz())
        // The first look at the catalogue may find several reached: six told, the rest summed up.
        for (a in avis.take(6)) annonce(a.sorte, a.texte, a.id)
        if (avis.size > 6) annonce("obtenable", (avis.size - 6).toString() + " autres diplômes à voir dans Agenda › Diplômes", "")
        // Remembered even when a kind of notice is off: turning it on later does not tell the past.
        val dits = Diplomes.aSignaler(bilans, f.dits, horloge(), r.jours, tz = tz()).map { it.cle }
        if (dits.isNotEmpty()) garde(donnees.copy(dits = donnees.dits + dits))
    }

    private fun annonce(sorte: String, texte: String, id: String) {
        annonces += Annonce(++numero, horloge(), sorte, texte, id)
        while (annonces.size > 30) annonces.removeAt(0)
        println("diplômes : $texte")
    }

    // ------------------------------------------------------------------ the request

    private fun mhz(hz: Long) = String.format(java.util.Locale.FRANCE, "%.3f", hz / 1e6)
    private fun utc(ms: Long, motif: String) = java.text.SimpleDateFormat(motif, java.util.Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))

    /**
     * What the form of award [id] asks, ready to copy: the station (from the
     * settings and « Pour les demandes » — never made up: a field not known is
     * said empty), the pictures that qualify ([noms] chosen, else the best
     * three) with their date, UTC time, frequency, mode and pass.
     */
    fun demande(id: String, noms: List<String> = emptyList()): JSONObject {
        val b = bilans().firstOrNull { it.diplome.id == id } ?: return JSONObject().put("ok", false).put("erreur", "diplôme inconnu")
        val d = b.diplome
        val s = station.settings
        val idt = donnees.identite
        val indicatif = s.callsign.trim().uppercase()
        val loc = station.etat.value.locator.trim()
        val nom = idt.nom.ifBlank { s.plancheNom.trim() }
        val champs = JSONArray()
        fun champ(k: String, libelle: String, v: String, d2: String = "") = champs.put(JSONObject().put("k", k).put("libelle", libelle).put("valeur", v).put("manque", v.isBlank()).put("aide", d2))
        champ("indicatif", "Indicatif", indicatif, "Réglages › Station")
        champ("nom", "Nom (sur le diplôme)", nom, "« Pour les demandes » ci-contre")
        champ("courriel", "Courriel", idt.courriel, "« Pour les demandes » ci-contre : SatMe ne le devine jamais")
        champ("locator", "Locator", loc, "Réglages › Station")
        fr.f4ioz.satcombo.sstv.PackAriss.region(loc).takeIf { it.isNotBlank() }?.let { champ("region", "Région du monde", it) }
        val choisies = if (noms.isNotEmpty()) noms.mapNotNull { n -> b.images.firstOrNull { it.nom == n } } else b.images.take(3)
        val imgs = JSONArray(choisies.map { i ->
            val o = runCatching { infosImage(i.nom) }.getOrNull() ?: JSONObject().put("nom", i.nom).put("quand", i.quandMs).put("sat", i.satNom).put("mode", i.mode)
                .put("note", i.note).put("complete", i.complete).put("freqHz", 0L)
            if (o.optLong("freqHz") <= 0 && d.freqHz > 0) o.put("freqHz", d.freqHz)
            val q = o.optLong("quand", i.quandMs)
            o.put("date", utc(q, "yyyy-MM-dd")).put("heure", utc(q, "HH:mm:ss") + " UTC")
                .put("frequence", o.optLong("freqHz").takeIf { it > 0 }?.let { mhz(it) + " MHz" } ?: "")
                .put("passage", if (o.has("aos") && !o.isNull("aos")) utc(o.getLong("aos"), "HH:mm") + "–" + utc(o.getLong("los"), "HH:mm") + " UTC, " + o.opt("elMax") + "° max" else "")
        })
        // The whole as text: what the owner pastes into a form or an e-mail.
        val sb = StringBuilder()
        sb.append(d.nom).append(" — ").append(d.emetteur).append('\n')
        for (i in 0 until champs.length()) champs.getJSONObject(i).let { c -> sb.append(c.getString("libelle")).append(" : ").append(c.getString("valeur").ifBlank { "(à remplir)" }).append('\n') }
        if (d.evenement) for (i in 0 until imgs.length()) imgs.getJSONObject(i).let { o ->
            sb.append('\n').append("Image ").append(i + 1).append(" : ").append(o.optString("nom")).append('\n')
            sb.append("  Date : ").append(o.optString("date")).append(" · heure : ").append(o.optString("heure")).append('\n')
            sb.append("  Satellite : ").append(o.optString("sat")).append(if (o.optString("frequence").isNotBlank()) " · " + o.optString("frequence") else "")
                .append(if (o.optString("mode").isNotBlank()) " · " + o.optString("mode") else "").append('\n')
            if (o.optString("passage").isNotBlank()) sb.append("  Passage : ").append(o.optString("passage")).append('\n')
        }
        if (!d.evenement) {
            sb.append('\n').append(d.genre.unite.replaceFirstChar { it.uppercase() }).append(" (").append(b.compte).append("/").append(d.objectif).append(") : ")
            sb.append(if (b.elements.isNotEmpty()) b.elements.joinToString(", ") else b.compte.toString()).append('\n')
        }
        val ariss = d.lienFormulaire == Diplomes.GALERIE_ARISS
        return JSONObject().put("ok", true).put("diplome", Diplomes.json(d)).put("libelle", b.libelle).put("statut", b.statut.name)
            .put("champs", champs).put("images", imgs).put("toutes", JSONArray(b.images.take(40).map { JSONObject().put("nom", it.nom).put("quand", it.quandMs).put("note", it.note).put("complete", it.complete) }))
            .put("elements", JSONArray(b.elements)).put("compte", b.compte).put("texte", sb.toString())
            .put("pack", if (ariss || d.evenement) "api/sstv-options/ariss.zip?noms=" + java.net.URLEncoder.encode(choisies.joinToString(",") { it.nom }, "UTF-8") else JSONObject.NULL)
            .put("conseil", if (ariss) "La galerie ARISS veut l'image telle que reçue (png, jpg ou gif, 800 ko et 800×800 au plus) : le pack contient l'original et une version QSL pour toi." else "")
    }

    // ------------------------------------------------------------------ the page

    private fun json(o: Any) = ReponseWeb("200 OK", "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))
    private fun refus(t: String) = json(JSONObject().put("ok", false).put("erreur", t))

    /** A flyer's picture read: the award it proposes (not kept: the owner checks it, then « Ajouter »). */
    fun affiche(octets: ByteArray): JSONObject {
        val img = runCatching { ImageIO.read(ByteArrayInputStream(octets)) }.getOrNull() ?: return JSONObject().put("ok", false).put("erreur", "image illisible (JPEG ou PNG)")
        val texte = ocr(img) ?: return JSONObject().put("ok", false).put("erreur", "tesseract n'a rien lu" +
            (if (OutilsPc.trouve("tesseract") == null) " : " + OutilsPc.conseil("tesseract") else ""))
        return proposition(texte, "Affiche")
    }

    fun proposition(texte: String, source: String = "Texte"): JSONObject {
        val sats = station.etat.value.satellites.map { it.name to it.catalogNumber }
        val l = Diplomes.lisAnnonce(texte, horloge(), tz(), sats, source)
        return JSONObject().put("ok", true).put("texte", texte.take(4000)).put("estDiplome", l.estDiplome).put("trouve", JSONArray(l.trouve))
            .put("proposition", Diplomes.json(l.diplome))
    }

    // ------------------------------------------------------------------ the web site

    /** « Publication web »: the awards and the pictures of those received, sent to the owner's site (set by Main). */
    @Volatile var site: DiplomesSitePc? = null

    /** The awards' message for the site ([DiplomesSitePc.json]); null while « Diplômes » is off here. */
    fun pourSite(): JSONObject? = if (!donnees.reglages.actif) null else DiplomesSitePc.json(bilans(), site?.envoyes()?.keys.orEmpty())

    /** The picture kept of award [id] received, if any. */
    fun certificat(id: String): File? = donnees.suivis[id]?.certificat?.takeIf { it.isNotBlank() }?.let { File(dossierCertificats, File(it).name) }?.takeIf { it.isFile }

    private fun suivi(id: String, s: Diplomes.Suivi) = garde(donnees.copy(suivis = donnees.suivis + (id to s)))

    private fun nomCertificat(id: String, ext: String) = id.replace(Regex("[^A-Za-z0-9_-]+"), "_").take(80) + "." + ext

    /**
     * `/api/diplomes`: GET the list (each award's state), the settings, the
     * sources, the notices; GET `/demande?id=&noms=` what the form asks,
     * `/certificat?id=` the certificate received. POST `/suivi?id=&etat=`,
     * `/certificat?id=` (a picture in the body), `/affiche` (a flyer's picture),
     * `/texte` (an announcement's text), `/ajoute` (an award as JSON),
     * `/retire?id=`, `/reglage?k=&on=` (or `&jours=`), `/identite?nom=&courriel=`, `/rafraichit`.
     */
    fun sert(chemin: String, methode: String, p: (String) -> String, corps: (Long) -> ByteArray?): ReponseWeb {
        val route = chemin.removePrefix("/api/diplomes")
        if (methode != "POST") return when (route) {
            "", "/" -> json(json())
            "/demande" -> json(demande(p("id"), p("noms").split(',').map { it.trim() }.filter { it.isNotEmpty() }))
            "/certificat" -> {
                val nom = donnees.suivis[p("id")]?.certificat?.takeIf { it.isNotBlank() } ?: return refus("aucun certificat")
                val f = File(dossierCertificats, File(nom).name).takeIf { it.isFile } ?: return refus("fichier absent")
                ReponseWeb("200 OK", if (f.name.endsWith(".png")) "image/png" else "image/jpeg", f.readBytes())
            }
            else -> refus("inconnu")
        }
        return when (route) {
            "/suivi" -> {
                val id = p("id"); if (id.isBlank()) return refus("id")
                val e = runCatching { Diplomes.EtatSuivi.valueOf(p("etat")) }.getOrNull() ?: return refus("état inconnu")
                val ancien = donnees.suivis[id] ?: Diplomes.Suivi()
                suivi(id, ancien.copy(etat = e, quandMs = if (e == Diplomes.EtatSuivi.AUCUN) 0L else horloge(), note = p("note").ifBlank { ancien.note }.take(300)))
                json(JSONObject().put("ok", true))
            }
            "/certificat" -> {
                val id = p("id"); if (id.isBlank()) return refus("id")
                val octets = corps(AutoPiloteSstvPc.MAX_IMAGE) ?: return refus("image trop grande (10 Mo au plus) ou absente")
                val img = runCatching { ImageIO.read(ByteArrayInputStream(octets)) }.getOrNull() ?: return refus("image illisible (JPEG ou PNG)")
                // Kept as sent when it is a PNG or a JPEG, else written again as a PNG.
                val png = octets.size > 4 && octets[1] == 'P'.code.toByte() && octets[2] == 'N'.code.toByte() && octets[3] == 'G'.code.toByte()
                val jpeg = octets.size > 3 && octets[0] == 0xFF.toByte() && octets[1] == 0xD8.toByte()
                val nom = nomCertificat(id, if (jpeg) "jpg" else "png")
                dossierCertificats.mkdirs()
                donnees.suivis[id]?.certificat?.takeIf { it.isNotBlank() && it != nom }?.let { File(dossierCertificats, File(it).name).delete() }
                if (png || jpeg) File(dossierCertificats, nom).writeBytes(octets) else ImageIO.write(img, "png", File(dossierCertificats, nom))
                val ancien = donnees.suivis[id] ?: Diplomes.Suivi()
                suivi(id, ancien.copy(etat = Diplomes.EtatSuivi.RECU, quandMs = if (ancien.etat == Diplomes.EtatSuivi.RECU && ancien.quandMs > 0) ancien.quandMs else horloge(),
                    certificat = nom))
                json(JSONObject().put("ok", true))
            }
            "/affiche" -> json(affiche(corps(AutoPiloteSstvPc.MAX_IMAGE) ?: return refus("image trop grande (10 Mo au plus) ou absente")))
            "/texte" -> json(proposition((corps(64_000L) ?: ByteArray(0)).toString(Charsets.UTF_8)))
            "/ajoute" -> {
                val o = runCatching { JSONObject((corps(64_000L) ?: ByteArray(0)).toString(Charsets.UTF_8)) }.getOrNull() ?: return refus("illisible")
                val d0 = Diplomes.lis(o) ?: return refus("diplôme incomplet")
                if (d0.id.startsWith("cat:")) return refus("id réservé")
                val d = d0.copy(source = if (d0.source == "Affiche") "Affiche" else "Texte", fictif = false)
                garde(donnees.copy(connus = donnees.connus.filter { it.id != d.id } + d))
                json(JSONObject().put("ok", true).put("id", d.id))
            }
            "/retire" -> {
                val id = p("id")
                if (donnees.connus.none { it.id == id && (it.source == "Affiche" || it.source == "Texte") }) return refus("seuls les diplômes ajoutés à la main se retirent (les autres se masquent)")
                garde(donnees.copy(connus = donnees.connus.filter { it.id != id }, suivis = donnees.suivis - id))
                json(JSONObject().put("ok", true))
            }
            "/reglage" -> {
                val on = p("on") == "1"
                val r = donnees.reglages
                val n = when (p("k")) {
                    "actif" -> r.copy(actif = on); "obtenable" -> r.copy(obtenable = on); "echeance" -> r.copy(echeance = on)
                    "nouveau" -> r.copy(nouveau = on); "sources" -> r.copy(sources = on)
                    "jours" -> r.copy(jours = (p("jours").toIntOrNull() ?: 3).coerceIn(1, 60))
                    else -> return refus("réglage inconnu")
                }
                garde(donnees.copy(reglages = n))
                if (on) forcer = true
                json(JSONObject().put("ok", true))
            }
            "/identite" -> {
                val c = p("courriel").trim()
                if (c.isNotEmpty() && !Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(c)) return refus("courriel invalide")
                garde(donnees.copy(identite = Diplomes.Identite(p("nom").trim().take(80), c.take(120))))
                json(JSONObject().put("ok", true))
            }
            "/rafraichit" -> { forcer = true; json(JSONObject().put("ok", true)) }
            // « Envoyer le diplôme reçu »: its picture to the owner's site, on this click only.
            "/envoie-site" -> {
                val id = p("id"); if (id.isBlank()) return refus("id")
                val s = site ?: return refus("Publication web absente")
                s.envoie(id, certificat(id))?.let { refus(it) } ?: json(JSONObject().put("ok", true))
            }
            else -> refus("inconnu")
        }
    }

    fun json(): JSONObject {
        val f = donnees
        val r = f.reglages
        val s = station.settings
        val l = if (r.actif) bilans() else emptyList()
        return JSONObject().put("ok", true).put("maintenant", horloge())
            .put("reglages", JSONObject().put("actif", r.actif).put("obtenable", r.obtenable).put("echeance", r.echeance).put("nouveau", r.nouveau)
                .put("jours", r.jours).put("sources", r.sources))
            .put("identite", JSONObject().put("nom", f.identite.nom).put("courriel", f.identite.courriel).put("indicatif", s.callsign)
                .put("locator", station.etat.value.locator).put("nomPlanche", s.plancheNom))
            .put("demo", !enLigneAutorise)
            .put("site", site?.pret == true)
            .put("sources", JSONArray(if (!enLigneAutorise) emptyList() else enLigne.etats().map { e ->
                JSONObject().put("id", e.src.name).put("nom", e.src.nom).put("lien", e.src.lien).put("tentative", e.tentativeMs)
                    .put("succes", e.succesMs).put("erreur", e.erreur).put("n", e.diplomes.size)
            }))
            .put("diplomes", JSONArray(l.map { b ->
                Diplomes.json(b.diplome).put("statut", b.statut.name).put("statutLibelle", b.statut.libelle).put("libelle", b.libelle).put("compte", b.compte)
                    .put("remarque", b.remarque).put("elements", JSONArray(b.elements.take(400)))
                    .put("images", JSONArray(b.images.take(6).map { JSONObject().put("nom", it.nom).put("quand", it.quandMs).put("note", it.note).put("complete", it.complete) }))
                    .put("nImages", b.images.size).put("ajoute", b.diplome.source == "Affiche" || b.diplome.source == "Texte")
                    .put("suivi", JSONObject().put("etat", b.suivi.etat.name).put("quand", b.suivi.quandMs).put("certificat", b.suivi.certificat.isNotBlank()).put("note", b.suivi.note)
                        .put("envoyeSite", site?.envoyes()?.get(b.diplome.id) ?: 0L))
            }))
            .put("annonces", JSONArray(synchronized(this) { annonces.toList() }.map { JSONObject().put("n", it.n).put("quand", it.quandMs).put("sorte", it.sorte).put("texte", it.texte).put("id", it.id) }))
    }

    companion object {
        /**
         * An award's flyer read by tesseract: enlarged, as it is and its
         * colours turned over (white letters on a coloured band read best
         * so); the reading that finds the most of an award's words kept, the
         * other's links and callsigns added.
         */
        fun lisAffiche(img: BufferedImage): String? {
            val prog = OutilsPc.trouve("tesseract") ?: return null
            val grand = if (img.width < 1600) AutoPiloteSstvPc.agrandit(img, 2) else img
            val textes = listOf(AutoPiloteSstvPc.inverse(grand), grand).mapNotNull { TextesDatvPc.tesseract(prog, it, 3, delaiS = 40, signes = null) }
                .filter { it.isNotBlank() }
            if (textes.isEmpty()) return null
            val mots = Regex("award|certific|dipl[oô]m|deadline|limite|\\d{1,2}[ ,-]+\\d{4}|MHz|SSTV|club", RegexOption.IGNORE_CASE)
            val meilleur = textes.maxByOrNull { mots.findAll(it).count() }!!
            val vues = meilleur.lines().map { it.trim() }.toSet()
            val plus = textes.filter { it !== meilleur }.flatMap { it.lines() }.map { it.trim() }
                .filter { l -> l !in vues && Regex("https?\\s?:|bit\\.ly|forms|\\b[A-Z]{1,2}\\d[A-Z]{1,3}\\b").containsMatchIn(l) }
            return (meilleur.trim() + "\n" + plus.distinct().joinToString("\n")).trim()
        }
    }
}
