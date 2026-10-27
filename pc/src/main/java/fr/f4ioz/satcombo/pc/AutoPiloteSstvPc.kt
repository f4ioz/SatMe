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
import fr.f4ioz.satcombo.data.Transmitter
import fr.f4ioz.satcombo.demo.ReponseWeb
import fr.f4ioz.satcombo.domain.ActivitesAVenir
import fr.f4ioz.satcombo.domain.AfficheSstv
import fr.f4ioz.satcombo.domain.CampagneSstv
import fr.f4ioz.satcombo.domain.CampagneSstv.Campagne
import fr.f4ioz.satcombo.domain.PiloteSstv
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * « Agenda › SSTV »: the SSTV campaigns kept in the data folder
 * (`sstv-campagnes.json`), their passes, and the auto-pilot that receives
 * them ([PiloteSstv]) once its master switch is on (off by default).
 *
 * **Receive only.** The chain goes through the paths the page itself uses:
 * the satellite followed, a « Montage » profile's receive settings, the
 * sound source, the SSTV decoder and the recording. The rig is connected by
 * itself only when the campaign says so, and then only by the IC-9700's
 * network link or the Icom's own USB port (its serial number names it);
 * the mast only by rotctld. Nothing ever transmits; a transmission on sets
 * the pass aside. Flyers are read by tesseract on this PC; a flyer's URL is
 * fetched only when the owner gives one.
 */
class AutoPiloteSstvPc(
    private val station: StationPc,
    dossier: File,
    private val horloge: () -> Long = System::currentTimeMillis,
    /** A satellite's passes between two instants (the station's predictor). */
    private val passagesDe: (Int, Long, Long) -> List<SatPass> = station::passagesDe,
    /** The text read in a picture (tesseract), null when it cannot be. */
    private val ocr: (BufferedImage) -> String? = ::lisImage,
    /** A flyer's bytes from its URL (the owner's). */
    private val telecharge: (String) -> ByteArray = ::telechargeImage,
    /** The station's gestures (the tests' fake); null: this PC's. */
    stationPilote: PiloteSstv.Station? = null,
) {
    private val fichier = File(dossier, "sstv-campagnes.json")
    @Volatile private var donnees: CampagneSstv.Fichier = runCatching { CampagneSstv.lit(fichier.readText()) }.getOrDefault(CampagneSstv.Fichier())

    // Set by Main: what the chain uses (absent in the tests).
    @Volatile var son: SonStation? = null
    @Volatile var sdr: SdrPc? = null
    @Volatile var montage: MontagePc? = null
    /** The decoders' source in one gesture (the page's own): null when done, else why not. */
    @Volatile var prendSource: ((String) -> String?)? = null
    /** A sentence said by the announcements (when they are on). */
    @Volatile var annonce: (String) -> Unit = {}
    /** A transmission on the air, said (DATV, SSTV, I/Q); null when none. */
    @Volatile var emissionEnCours: () -> String? = { null }

    val pilote = PiloteSstv(stationPilote ?: StationReelle(), horloge) { consigne(it) }

    val maitre: Boolean get() = donnees.maitre
    val campagnes: List<Campagne> get() = donnees.campagnes
    /** What became of the passes (done, set aside…), for the event's report. */
    val historique: List<CampagneSstv.Trace> get() = donnees.historique

    // ------------------------------------------------------------------ passes

    /** Each campaign's passes, worked out again every 10 minutes (or when it changes). */
    private val cache = HashMap<String, Triple<Long, Campagne, List<SatPass>>>()

    /** How far ahead passes are worked out. */
    private val HORIZON_MS = 15 * 86_400_000L

    private fun passages(c: Campagne): List<SatPass> {
        val t = horloge()
        synchronized(cache) {
            cache[c.id]?.let { (quand, cc, l) -> if (cc == c && t - quand < 10 * 60_000L && (l.isNotEmpty() || t - quand < 60_000L)) return l }
        }
        val de = maxOf(c.debutMs, t - 3 * 3_600_000L)
        val a = minOf(c.finMs, t + HORIZON_MS)
        val l = if (a <= de) emptyList() else runCatching { passagesDe(c.catnum, de - 30 * 60_000L, a) }.getOrDefault(emptyList())
        synchronized(cache) { cache[c.id] = Triple(t, c, l) }
        return l
    }

    fun plan(): List<CampagneSstv.Prevu> {
        val d = donnees
        return CampagneSstv.plan(d.campagnes, ::passages, d.historique, horloge(), pilote.enCours?.let { it.campagne.id to it.passage.aosEpochMs })
    }

    /** The active campaigns' satellites (the timeline shows them). */
    fun catnums(): Set<Int> = donnees.campagnes.filter { it.active && it.finMs > horloge() }.map { it.catnum }.toSet()

    // ------------------------------------------------------------------ running

    @Volatile private var repris = false
    @Volatile private var dernierEnCours: String? = null

    /** Once a second: the pass under way kept on disk taken up after a restart, then the auto-pilot's turn. */
    @Synchronized fun tic() {
        if (!repris) {
            repris = true
            donnees.enCours?.let { e ->
                val c = donnees.campagnes.firstOrNull { it.id == e.campagne }
                val p = c?.let { passages(it) }?.firstOrNull { kotlin.math.abs(it.aosEpochMs - e.aosMs) <= CampagneSstv.TOLERANCE_MS }
                if (c != null && p != null && donnees.maitre) pilote.reprend(c, p, e.avant)
            }
        }
        pilote.tic(plan(), donnees.maitre)
        val ec = pilote.enCours
        val cle = ec?.let { it.campagne.id + ":" + it.passage.aosEpochMs }
        if (cle != dernierEnCours) {
            dernierEnCours = cle
            donnees = donnees.copy(enCours = ec?.let { CampagneSstv.EnCoursSauve(it.campagne.id, it.passage.aosEpochMs, it.avant) })
            ecrit()
        }
        son?.noteSstv = ec?.let { "Campagne : " + it.campagne.nom } ?: ""
    }

    private fun consigne(t: CampagneSstv.Trace) {
        synchronized(this) {
            donnees = donnees.copy(historique = (donnees.historique + t).takeLast(CampagneSstv.MAX_HISTORIQUE))
            ecrit()
        }
        println("auto-pilote SSTV : ${t.nom} ${t.statut.name.lowercase()}" + if (t.raison.isNotBlank()) " (${t.raison})" else "")
    }

    private fun ecrit() = runCatching {
        fichier.parentFile?.mkdirs()
        val tmp = File(fichier.parentFile, fichier.name + ".tmp")
        tmp.writeText(CampagneSstv.ecrit(donnees).toString(2))
        if (!tmp.renameTo(fichier)) { fichier.delete(); tmp.renameTo(fichier) }
    }

    fun regleMaitre(on: Boolean) = synchronized(this) { donnees = donnees.copy(maitre = on); ecrit() }

    /** A campaign kept (new, or replacing the one of the same id): it, or why not. */
    fun garde(c: Campagne): Result<Campagne> = synchronized(this) {
        val v = CampagneSstv.valide(c).getOrElse { return Result.failure(it) }
        val ancien = donnees.campagnes.firstOrNull { it.id == c.id && c.id.isNotBlank() }
        if (ancien == null && donnees.campagnes.size >= CampagneSstv.MAX) return Result.failure(IllegalStateException("${CampagneSstv.MAX} campagnes au plus"))
        val k = if (ancien != null) v.copy(id = ancien.id, creeMs = ancien.creeMs) else v.copy(id = CampagneSstv.nouvelId(v.nom, donnees.campagnes), creeMs = horloge())
        donnees = donnees.copy(campagnes = if (ancien != null) donnees.campagnes.map { if (it.id == k.id) k else it } else donnees.campagnes + k)
        synchronized(cache) { cache.remove(k.id) }
        ecrit()
        Result.success(k)
    }

    fun retire(id: String) = synchronized(this) {
        if (pilote.enCours?.campagne?.id == id) pilote.stop("campagne retirée")
        donnees = donnees.copy(campagnes = donnees.campagnes.filter { it.id != id }); ecrit()
    }

    fun active(id: String, on: Boolean) = synchronized(this) {
        donnees = donnees.copy(campagnes = donnees.campagnes.map { if (it.id == id) it.copy(active = on) else it }); ecrit()
    }

    // ------------------------------------------------------------- from an activity

    /** What « Activer l'auto-pilote » did: the campaign, made now or already there, the master switch turned on by it. */
    data class Activation(val campagne: Campagne, val nouvelle: Boolean, val maitreAllume: Boolean)

    /** The campaign made from [a] in one click, if any: by its id, else (an activity merged since) the same satellite over the same period. */
    fun campagneDe(a: ActivitesAVenir.Activite): Campagne? = donnees.campagnes.let { l ->
        l.firstOrNull { it.activite.isNotBlank() && it.activite == a.id }
            ?: l.firstOrNull { it.activite.isNotBlank() && a.catnum > 0 && it.catnum == a.catnum &&
                it.debutMs <= a.finMs + ActivitesAVenir.MARGE_MS && it.finMs >= a.debutMs - ActivitesAVenir.MARGE_MS }
    }

    /**
     * [a]'s campaign as « Préparer la campagne » fills the form, with the
     * form's defaults (chain: no profile, the rig's sound, rig and mast
     * followed, recorded, decoded, said): on satellite [catnum]; a period
     * known by whole days taken from 00:00 to 23:59 here (as the form does);
     * without a frequency, the satellite's SSTV transmitter (else its FM one);
     * passes from [elMin] up (never the station's 0°: 1–2° passes give nothing).
     */
    fun depuisActivite(a: ActivitesAVenir.Activite, catnum: Int, elMin: Double = CampagneSstv.EL_MIN_ACTIVITE): Result<Campagne> {
        if (catnum <= 0) return Result.failure(IllegalArgumentException("choisir le satellite"))
        val t = horloge()
        val b = ActivitesAVenir.campagne(a.copy(catnum = catnum), t)
        val sat = station.etat.value.satellites.firstOrNull { it.catalogNumber == catnum }
        var de = b.debutMs; var fin = b.finMs
        if (b.aConfirmer) {
            val z = java.time.ZoneId.systemDefault()
            de = java.time.Instant.ofEpochMilli(de).atZone(z).toLocalDate().atStartOfDay(z).toInstant().toEpochMilli()
            fin = java.time.Instant.ofEpochMilli(fin).atZone(z).toLocalDate().atTime(23, 59).atZone(z).toInstant().toEpochMilli()
        }
        if (fin <= t) return Result.failure(IllegalArgumentException("activité terminée"))
        if (fin - de > CampagneSstv.DUREE_MAX_MS) fin = de + CampagneSstv.DUREE_MAX_MS
        val emetteur = if (b.freqHz > 0) "" else {
            val l = station.emetteursDe(catnum).filter { it.alive && it.downlinkLowHz != null }
            (l.firstOrNull { (it.description + " " + it.mode.orEmpty()).contains("SSTV", true) }
                ?: l.firstOrNull { it.mode.orEmpty().uppercase() in CampagneSstv.MODULATIONS })?.description
                ?: return Result.failure(IllegalArgumentException("fréquence non annoncée et aucun émetteur SSTV ou FM connu pour " +
                    (sat?.name ?: a.satNom.ifBlank { "ce satellite" }) + " : « Modifier avant… » pour la donner"))
        }
        return CampagneSstv.valide(b.copy(satNom = sat?.name ?: a.satNom.ifBlank { "#$catnum" }, emetteur = emetteur,
            debutMs = de, finMs = fin, elMin = elMin, activite = a.id, activiteSuit = true, chaine = CampagneSstv.Chaine())).map { it.copy(id = "") }
    }

    /**
     * « Activer l'auto-pilote » on [a] (satellite [catnum]): its campaign
     * made and kept, or the one already made switched on again (with the
     * elevation [elMin] confirmed); the master switch turned on when it was
     * off. Receive only, as always.
     */
    fun activeActivite(a: ActivitesAVenir.Activite, catnum: Int, elMin: Double = CampagneSstv.EL_MIN_ACTIVITE): Result<Activation> = synchronized(this) {
        val deja = campagneDe(a.copy(catnum = catnum))
        val c = if (deja != null) {
            if (!deja.active) active(deja.id, true)
            val k = donnees.campagnes.first { it.id == deja.id }
            if (k.elMin == elMin) k else garde(k.copy(elMin = elMin)).getOrElse { return Result.failure(it) }
        } else garde(depuisActivite(a, catnum, elMin).getOrElse { return Result.failure(it) }).getOrElse { return Result.failure(it) }
        val allume = !donnees.maitre
        if (allume) regleMaitre(true)
        Result.success(Activation(c, deja == null, allume))
    }

    /**
     * After each refresh of the activities: a campaign made from one follows
     * its new period while the owner has not changed that period himself
     * (an activity without dates is left alone — its period would only creep).
     * True when a campaign changed.
     */
    fun suitActivites(l: List<ActivitesAVenir.Activite>): Boolean = synchronized(this) {
        var change = false
        val nouvelles = donnees.campagnes.map { c ->
            if (c.activite.isBlank() || !c.activiteSuit) return@map c
            val a = l.firstOrNull { it.id == c.activite } ?: l.firstOrNull { it.catnum == c.catnum && campagneDe(it)?.id == c.id } ?: return@map c
            if (a.precision == ActivitesAVenir.Precision.INCONNUE || a.type != ActivitesAVenir.Type.SSTV) return@map c
            val n = depuisActivite(a, c.catnum).getOrNull() ?: return@map c
            if (n.debutMs == c.debutMs && n.finMs == c.finMs && n.aConfirmer == c.aConfirmer && a.id == c.activite) return@map c
            change = true
            c.copy(debutMs = n.debutMs, finMs = n.finMs, aConfirmer = n.aConfirmer, activite = a.id)
        }
        if (change) {
            donnees = donnees.copy(campagnes = nouvelles)
            synchronized(cache) { cache.clear() }
            ecrit()
        }
        change
    }

    // ------------------------------------------------------------------ the flyer

    /** A flyer's picture read: its text, and the campaign's form it proposes. */
    fun affiche(octets: ByteArray): JSONObject {
        val img = runCatching { ImageIO.read(ByteArrayInputStream(octets)) }.getOrNull()
            ?: return JSONObject().put("ok", false).put("erreur", "image illisible (JPEG ou PNG)")
        val texte = ocr(img) ?: return JSONObject().put("ok", false).put("erreur", "tesseract n'a rien lu" +
            (if (OutilsPc.trouve("tesseract") == null) " : " + OutilsPc.conseil("tesseract") else ""))
        return proposition(texte)
    }

    /** The form proposed for a flyer's [texte]. */
    fun proposition(texte: String): JSONObject {
        val sats = station.etat.value.satellites.map { it.name to it.catalogNumber }
        val p = AfficheSstv.lis(texte, horloge(), TimeZone.getDefault(), sats)
        val sat = station.etat.value.satellites.firstOrNull { it.catalogNumber == p.catnum }
        return JSONObject().put("ok", true).put("texte", texte.take(4000)).put("proposition", JSONObject()
            .put("nom", p.nom).put("catnum", p.catnum).put("sat", sat?.name ?: p.satNom).put("freqHz", p.freqHz).put("mode", p.modeSstv)
            .put("de", p.debutMs).put("a", p.finMs).put("aConfirmer", p.aConfirmer).put("horaire", p.horaire).put("lien", p.lien)
            .put("trouve", JSONArray(p.trouve)))
    }

    // ------------------------------------------------------------------ the page

    private fun json(o: Any) = ReponseWeb("200 OK", "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))
    private fun refus(t: String) = json(JSONObject().put("ok", false).put("erreur", t))

    /**
     * `/api/autopilote`: GET the state; POST `/maitre?on=`, `/campagne`
     * (the form), `/retire?id=`, `/active?id=&on=`, `/stop`, `/affiche`
     * (a picture in the body, or ?url=), `/texte` (a flyer's text in the
     * body); GET `/emetteurs?catnum=`.
     */
    fun sert(chemin: String, methode: String, p: (String) -> String, corps: (Long) -> ByteArray?): ReponseWeb {
        val route = chemin.removePrefix("/api/autopilote")
        if (methode != "POST") return when (route) {
            "", "/" -> json(json())
            "/emetteurs" -> json(emetteurs(p("catnum").toIntOrNull() ?: 0))
            else -> refus("inconnu")
        }
        return when (route) {
            "/maitre" -> { regleMaitre(p("on") == "1"); json(JSONObject().put("ok", true).put("maitre", maitre)) }
            "/campagne" -> garde(depuisFormulaire(p).let { f ->
                // Made from an activity: still linked to it; its period no longer follows once changed here.
                val ancien = donnees.campagnes.firstOrNull { it.id == f.id && f.id.isNotBlank() }
                if (ancien == null || ancien.activite.isBlank()) f
                else f.copy(activite = ancien.activite, activiteSuit = ancien.activiteSuit && f.debutMs == ancien.debutMs && f.finMs == ancien.finMs)
            }).fold({ json(JSONObject().put("ok", true).put("campagne", CampagneSstv.json(it))) }, { refus(it.message ?: "refusé") })
            "/retire" -> { retire(p("id")); json(JSONObject().put("ok", true)) }
            "/active" -> { active(p("id"), p("on") == "1"); json(JSONObject().put("ok", true)) }
            "/stop" -> { if (pilote.enCours == null) refus("aucun passage en cours") else { pilote.stop(); tic(); json(JSONObject().put("ok", true)) } }
            "/affiche" -> {
                val url = p("url").trim()
                val octets = if (url.isNotEmpty()) {
                    if (!Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(url) || Catalogue.interdit(url)) return refus("adresse refusée")
                    runCatching { telecharge(url) }.getOrElse { return refus("image non reçue : " + (it.message ?: "erreur")) }
                } else corps(MAX_IMAGE) ?: return refus("image trop grande (10 Mo au plus) ou absente")
                json(affiche(octets))
            }
            "/texte" -> json(proposition((corps(64_000L) ?: ByteArray(0)).toString(Charsets.UTF_8)))
            else -> refus("inconnu")
        }
    }

    private fun depuisFormulaire(p: (String) -> String): Campagne {
        fun b(k: String, d: Boolean) = p(k).let { if (it.isEmpty()) d else it == "1" }
        val mhz = p("freq").replace(',', '.').trim().toDoubleOrNull() ?: 0.0
        val catnum = p("catnum").toIntOrNull() ?: 0
        return Campagne(id = p("id"), nom = p("nom"), catnum = catnum,
            satNom = station.etat.value.satellites.firstOrNull { it.catalogNumber == catnum }?.name ?: p("sat"),
            emetteur = if (mhz > 0) "" else p("emetteur"), freqHz = (mhz * 1e6).toLong(), modulation = p("modulation").ifBlank { "FM" },
            largeurHz = ((p("largeur").replace(',', '.').toDoubleOrNull() ?: 15.0) * 1000).toInt(),
            modeSstv = p("mode").ifBlank { CampagneSstv.AUTO }, debutMs = p("de").toLongOrNull() ?: 0L, finMs = p("a").toLongOrNull() ?: 0L,
            aConfirmer = b("aconfirmer", false), elMin = p("el").replace(',', '.').toDoubleOrNull() ?: 10.0, horaire = p("horaire"),
            avantMin = p("avant").toIntOrNull() ?: 2, notes = p("notes"), lien = p("lien"), active = b("active", true),
            chaine = CampagneSstv.Chaine(profil = p("profil"), poste = b("poste", true), connecterPoste = b("cposte", false),
                source = p("source"), rotor = b("rotor", true), connecterRotor = b("crotor", false), enregistrer = b("enreg", true),
                sstv = b("sstv", true), annonce = b("annonce", true), parquer = b("parquer", false)))
    }

    private fun emetteurs(catnum: Int): JSONObject = JSONObject().put("ok", true).put("emetteurs", JSONArray(
        station.emetteursDe(catnum).filter { it.alive && it.downlinkLowHz != null }.map { t ->
            JSONObject().put("description", t.description).put("mode", t.mode.orEmpty()).put("bas", t.downlinkLowHz).put("haut", t.downlinkHighHz ?: t.downlinkLowHz)
        }))

    fun json(): JSONObject {
        val t = horloge()
        val ec = pilote.enCours
        val plan = runCatching { plan() }.getOrDefault(emptyList())
        val s = son
        val cartes = s?.let { runCatching { val l = it.son.appareils(); val n = it.nomsCartes(l); l.map { a -> JSONObject().put("nom", a.nom).put("lib", n[a.nom]?.libelle ?: a.nom) } }.getOrNull() }.orEmpty()
        return JSONObject().put("ok", true).put("maitre", donnees.maitre).put("maintenant", t)
            .put("campagnes", JSONArray(donnees.campagnes.map { c ->
                val l = plan.filter { it.campagne.id == c.id }
                CampagneSstv.json(c).put("prochain", l.firstOrNull { it.statut == CampagneSstv.Statut.PREVU }?.passage?.aosEpochMs ?: JSONObject.NULL)
                    .put("nb", l.count { it.statut == CampagneSstv.Statut.PREVU })
            }))
            .put("prochains", JSONArray(plan.take(60).map { pr ->
                JSONObject().put("campagne", pr.campagne.id).put("nom", pr.campagne.nom).put("sat", pr.campagne.satNom).put("catnum", pr.passage.catalogNumber)
                    .put("aos", pr.passage.aosEpochMs).put("los", pr.passage.losEpochMs).put("el", Math.round(pr.passage.maxElevationDeg))
                    .put("prep", pr.passage.aosEpochMs - pr.campagne.avantMs).put("statut", pr.statut.name).put("raison", pr.raison)
            }))
            .put("enCours", ec?.let { e ->
                JSONObject().put("campagne", e.campagne.id).put("nom", e.campagne.nom).put("sat", e.campagne.satNom).put("phase", e.phase.name)
                    .put("aos", e.passage.aosEpochMs).put("los", e.passage.losEpochMs).put("el", Math.round(e.passage.maxElevationDeg))
                    .put("mode", e.campagne.modeSstv).put("freqHz", frequence(e.campagne)).put("faits", JSONArray(e.faits))
                    .put("enregistrement", s?.etat?.value?.enregistrement.orEmpty())
                    .put("images", runCatching { s?.images()?.count { it.timeMs >= e.passage.aosEpochMs - 60_000L } ?: 0 }.getOrDefault(0))
            } ?: JSONObject.NULL)
            .put("historique", JSONArray(donnees.historique.takeLast(30).reversed().let { hs ->
                // « Réunir »: a pass's partial pictures that are one picture cut in two (left after the pass, or from before).
                val paires = if (hs.isEmpty()) emptyList() else runCatching { fr.f4ioz.satcombo.sstv.ReunionSstv.paires(s?.sstv?.images().orEmpty().filter { !SstvPc.horsAntenne(it) }) }.getOrDefault(emptyList())
                hs.map { tr -> tr.json().put("reunir", JSONArray(paires.filter { (a, b) -> a.fileName in tr.images && b.fileName in tr.images }.map { it.first.fileName })) }
            }))
            .put("profils", JSONArray(montage?.profilsGardes().orEmpty().map { JSONObject().put("id", it.id).put("nom", it.nom) }))
            .put("cartes", JSONArray(cartes)).put("sdr", sdr != null)
            .put("modes", JSONArray(CampagneSstv.MODES))
            .put("tesseract", OutilsPc.trouve("tesseract") != null)
    }

    /** The frequency a campaign listens to (sky Hz): its own, else its transmitter's. */
    private fun frequence(c: Campagne): Long = if (c.personnalisee) c.freqHz
        else station.emetteursDe(c.catnum).firstOrNull { it.description == c.emetteur }?.downlinkLowHz ?: 0L

    // ------------------------------------------------------------- this PC's station

    /** The station's gestures, through what the page itself uses. Receive only. */
    private inner class StationReelle : PiloteSstv.Station {

        override fun emission(): String? = emissionEnCours() ?: montage?.etatFonctions()?.emission()

        override fun conflit(c: Campagne): String? {
            val i = montage?.etatFonctions()
            val src = c.chaine.source
            if (src == CampagneSstv.SDR) {
                sdr?.let { d -> if (d.pourSonde || d.pourMeteor) return "le récepteur SDR sert aux radiosondes ou à METEOR" }
                if (i != null && i.datvActif && i.datvSdrTenu.isNotBlank()) return "la DATV tient le récepteur SDR (" + i.datvSdrTenu + ")"
            }
            // The automatic mode following another satellite now.
            if (station.veille) {
                val p = fr.f4ioz.satcombo.domain.VeilleAuto.aSuivre(station.planVeille(), horloge())
                if (p != null && p.catalogNumber != c.catnum) return "le mode automatique suit " + p.satName
            }
            return null
        }

        override fun capture(): Map<String, String> {
            val e = station.etat.value
            val r = station.stationRotor.etat.value
            val m = LinkedHashMap<String, String>()
            m["suivi"] = e.suivi?.catalogNumber?.toString().orEmpty()
            m["emetteur"] = station.emetteurChoisi()?.description.orEmpty()
            m["catConnecte"] = if (e.catConnecte) "1" else "0"
            m["sousHorizon"] = if (station.catSousHorizon) "1" else "0"
            m["rotorSuit"] = if (r.rotorEnabled) "1" else "0"
            m["rotorConnecte"] = if (r.rotorConnected) "1" else "0"
            son?.let { s -> m["sstvForce"] = s.sstv.force?.name.orEmpty(); m["sstvActif"] = if (s.sstvActif) "1" else "0" }
            montage?.reglagesActuels()?.forEach { (k, v) -> m["r:$k"] = v }
            return m
        }

        override fun prepare(c: Campagne, p: SatPass): PiloteSstv.Preparation {
            val faits = ArrayList<String>()
            // 1. A « Montage » profile: its receive settings first.
            if (c.chaine.profil.isNotBlank()) {
                val pr = montage?.profilsGardes()?.firstOrNull { it.id == c.chaine.profil }
                    ?: return PiloteSstv.Preparation(faits, "profil « ${c.chaine.profil} » introuvable (Montage)")
                val b = montage?.appliqueReglages(pr.reglages) ?: return PiloteSstv.Preparation(faits, "profils indisponibles ici")
                b.refus?.let { return PiloteSstv.Preparation(faits, it) }
                faits += "profil « ${pr.nom} » appliqué"
            }
            // 2. The satellite followed, on the campaign's transmitter or its own frequency.
            val sat = station.etat.value.satellites.firstOrNull { it.catalogNumber == c.catnum }
                ?: return PiloteSstv.Preparation(faits, "${c.satNom} absent des éléments orbitaux")
            val perso = if (c.personnalisee) Transmitter("SSTV " + mhz(c.freqHz) + " MHz (" + c.nom + ")", c.modulation, null, null,
                c.freqHz, c.freqHz, false, true, "Transmitter") else null
            station.suis(sat) { l ->
                if (perso != null) (listOf(perso) + l) to 0
                else l to l.indexOfFirst { it.description == c.emetteur }.coerceAtLeast(0)
            }
            faits += sat.name + " suivi" + (if (perso != null) ", " + mhz(c.freqHz) + " MHz " + c.modulation else if (c.emetteur.isNotBlank()) ", " + c.emetteur else "")
            // 3. The rig: tuned (Doppler) even before AOS while the pass is prepared.
            if (c.chaine.poste) {
                station.catSousHorizon = true
                faits += if (station.etat.value.catConnecte) "poste : Doppler suivi" + (if (station.etat.value.catSimule) " (simulé)" else "")
                    else if (c.chaine.connecterPoste) connectePoste() else "poste non connecté : la fréquence ne suivra pas"
            }
            // 4. The mast.
            if (c.chaine.rotor) faits += rotor(c.chaine.connecterRotor)
            // 5. The sound source.
            val s = son
            if (s != null && c.chaine.source.isNotBlank()) {
                val nom = when (c.chaine.source) {
                    CampagneSstv.POSTE -> s.sourceDuPoste() ?: return PiloteSstv.Preparation(faits, "le son du poste est introuvable (carte USB ou réseau)")
                    CampagneSstv.SDR -> fr.f4ioz.satcombo.domain.SourceAudio.SDR
                    else -> c.chaine.source
                }
                prendSource?.invoke(nom)?.let { return PiloteSstv.Preparation(faits, "son : $it") }
                faits += "son : " + (if (nom == fr.f4ioz.satcombo.domain.SourceAudio.SDR) "récepteur SDR" else (s.nomsCartes()[nom]?.libelle ?: nom))
            }
            // 6. The SSTV decoder, held on the campaign's mode.
            if (s != null && c.chaine.sstv) {
                s.sstvActif = true
                s.sstv.force = c.modeForce
                faits += "SSTV prêt (" + (c.modeForce?.name ?: "mode auto") + ")"
            }
            return PiloteSstv.Preparation(faits)
        }

        /** The rig connected by itself: the IC-9700 by the network, or an Icom's own USB port named by its serial number — nothing else. */
        private fun connectePoste(): String {
            val st = station.settings
            val m = StationPc.modeleDe(st.rigModel)
            if (st.icomLiaison == "RESEAU" && m == "IC9700") {
                station.connecteCat(StationPc.ChoixCat(m))
                return "poste : connexion de l'IC-9700 par le réseau"
            }
            if (m !in StationPc.ICOMS) return "poste ${m} : à connecter à la main (port série)"
            if (PortsSeriePc.sansSerie()) return "ports série désactivés : poste non connecté"
            val modele = fr.f4ioz.satcombo.cat.ModeleIcom.de(m).libelle.removePrefix("Icom ").replace("-", "-?")
            val l = station.ports.liste()
            val siens = l.filter { pt -> pt.serie?.trim()?.let { Regex("^$modele[ _]\\S+[ _]A$", RegexOption.IGNORE_CASE).matches(it) } == true }
            val pt = siens.singleOrNull() ?: return "poste : son port USB n'est pas reconnu, à connecter à la main"
            station.connecteCat(StationPc.ChoixCat(m, port = l.indexOf(pt)))
            return "poste : connexion par " + pt.libelle
        }

        private fun rotor(connecter: Boolean): String {
            val r = station.stationRotor
            val e = r.etat.value
            if (e.rotorConnected) { r.setRotorEnabled(true); return "rotor : suit le satellite" }
            if (!connecter) return "rotor non connecté"
            if (e.rotorLink != "ROTCTLD" && !e.rotorSim) {
                // A serial controller only when it is the very one the owner connected before: the port at the
                // saved index must carry the saved serial number (an index alone may now be a rig — RTS/DTR key them).
                val cle = station.settings.rotorUsbCle.trim()
                if (cle.isEmpty()) return "rotor sur port série : connecte-le une fois à la main (son port sera alors reconnu)"
                val l = station.ports.liste()
                val pt = l.getOrNull(e.rotorUsbIndex)
                if (pt == null || pt.cle != cle) return "rotor : son port USB (" + cle + ") n'est pas à sa place, à connecter à la main"
                r.connectRotor(); r.setRotorEnabled(true)
                return "rotor : connexion par " + pt.libelle + ", suit le satellite"
            }
            r.connectRotor(); r.setRotorEnabled(true)
            return "rotor : connexion " + (if (e.rotorSim) "simulée" else "rotctld") + ", suit le satellite"
        }

        // The pass's recording, also when the automatic pass recording had started it first and stopped it at LOS
        // (the 13:08 pass on 22/10 kept no name for its recording).
        @Volatile private var enregistrementDuPassage = ""

        override fun enregistre(c: Campagne): String? {
            val s = son ?: return "pas de carte son ici"
            return s.demarreEnregistrement(auto = true).also { if (it == null) enregistrementDuPassage = s.etat.value.enregistrement }
        }

        override fun arreteEnregistrement(): String {
            val s = son ?: return ""
            val nom = s.etat.value.enregistrement.ifBlank { enregistrementDuPassage }
            enregistrementDuPassage = ""
            s.arreteEnregistrement(parLaMain = false)
            return nom
        }

        override fun images(deMs: Long, aMs: Long): List<String> =
            son?.images()?.filter { it.timeMs in deMs..aMs && !SstvPc.horsAntenne(it) }?.map { it.fileName }.orEmpty()

        override fun reunitPartielles(deMs: Long, aMs: Long): List<String> = son?.sstv?.reunitPartielles(deMs, aMs).orEmpty()

        override fun parque(): String? {
            val r = station.stationRotor
            if (!r.etat.value.rotorConnected) return "rotor non connecté"
            r.setRotorEnabled(false); r.rotorParkNow()
            return null
        }

        override fun restaure(avant: Map<String, String>): List<String> {
            val faits = ArrayList<String>()
            son?.let { s ->
                s.sstv.force = fr.f4ioz.satcombo.sstv.SstvMode.byName(avant["sstvForce"].orEmpty())
                avant["sstvActif"]?.let { s.sstvActif = it == "1" }
            }
            val reglages = avant.filterKeys { it.startsWith("r:") }.mapKeys { it.key.removePrefix("r:") }
            if (reglages.isNotEmpty()) montage?.appliqueReglages(reglages)?.let { b ->
                faits += b.refus ?: "réception remise (${b.resultats.count { it.ok }} réglage" + (if (b.resultats.count { it.ok } > 1) "s)" else ")")
            }
            avant["sousHorizon"]?.let { station.catSousHorizon = it == "1" }
            if (avant["catConnecte"] == "0" && station.etat.value.catConnecte) { station.deconnecteCat(); faits += "poste déconnecté" }
            val r = station.stationRotor
            if (avant["rotorConnecte"] == "0" && r.etat.value.rotorConnected) { r.disconnectRotor(); faits += "rotor déconnecté" }
            else avant["rotorSuit"]?.let { r.setRotorEnabled(it == "1") }
            val n = avant["suivi"]?.toIntOrNull()
            val e = station.etat.value
            if (n != null && n != e.suivi?.catalogNumber) e.satellites.firstOrNull { it.catalogNumber == n }?.let { sat ->
                val d = avant["emetteur"].orEmpty()
                station.suis(sat) { l -> l to l.indexOfFirst { it.description == d }.coerceAtLeast(0) }
                faits += sat.name + " suivi de nouveau"
            } else {
                // The same satellite (or none before): its own transmitter back in place of the campaign's frequency.
                val d = avant["emetteur"].orEmpty()
                e.suivi?.let { sat -> station.suis(sat) { l -> l to l.indexOfFirst { it.description == d }.coerceAtLeast(0) } }
            }
            son?.noteSstv = ""
            return faits
        }

        override fun annonce(texte: String) = this@AutoPiloteSstvPc.annonce(texte)
    }

    companion object {
        const val MAX_IMAGE = 10L * 1024 * 1024

        fun mhz(hz: Long): String = String.format(Locale.FRANCE, "%.3f", hz / 1e6)

        /** A flyer read by tesseract: as it is, then enlarged, then its colours turned over, until a frequency comes out. */
        fun lisImage(img: BufferedImage): String? {
            val prog = OutilsPc.trouve("tesseract") ?: return null
            val utile = Regex("\\d\\s*[MK]Hz", RegexOption.IGNORE_CASE)
            var premier: String? = null
            for (vue in sequenceOf({ img }, { if (img.width < 1000) agrandit(img, 2) else img }, { inverse(img) })) {
                val t = TextesDatvPc.tesseract(prog, vue(), 3, delaiS = 40, signes = null) ?: continue
                if (utile.containsMatchIn(t)) return t
                if (premier == null) premier = t
            }
            return premier
        }

        internal fun agrandit(img: BufferedImage, k: Int): BufferedImage {
            val g = BufferedImage(img.width * k, img.height * k, BufferedImage.TYPE_INT_RGB)
            g.createGraphics().apply {
                setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                drawImage(img, 0, 0, g.width, g.height, null); dispose()
            }
            return g
        }

        internal fun inverse(img: BufferedImage): BufferedImage {
            val o = BufferedImage(img.width, img.height, BufferedImage.TYPE_BYTE_GRAY)
            for (y in 0 until img.height) for (x in 0 until img.width) {
                val p = img.getRGB(x, y)
                val v = 255 - ((p shr 16 and 255) * 299 + (p shr 8 and 255) * 587 + (p and 255) * 114) / 1000
                o.raster.setSample(x, y, 0, v)
            }
            return o
        }

        private val client by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build() }

        /** A flyer from its URL: an image, 10 MB at most. */
        fun telechargeImage(url: String): ByteArray {
            require(!Catalogue.interdit(url)) { "source interdite" }
            client.newCall(Request.Builder().url(url).header("User-Agent", fr.f4ioz.satcombo.data.AgentHttp.USER_AGENT).build()).execute().use { r ->
                check(r.isSuccessful) { "HTTP ${r.code}" }
                val b = r.body ?: error("réponse vide")
                check((b.contentLength()) <= MAX_IMAGE) { "image trop grande" }
                val octets = b.byteStream().use { it.readNBytes(MAX_IMAGE.toInt() + 1) }
                check(octets.size <= MAX_IMAGE) { "image trop grande" }
                return octets
            }
        }
    }
}
