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
import fr.f4ioz.satcombo.demo.ReponseWeb
import fr.f4ioz.satcombo.domain.CampagneSstv
import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.sstv.AlerteDiapo
import fr.f4ioz.satcombo.sstv.OptionsSstv
import fr.f4ioz.satcombo.sstv.PackAriss
import fr.f4ioz.satcombo.sstv.Planche
import fr.f4ioz.satcombo.sstv.QslSstv
import fr.f4ioz.satcombo.sstv.RetentionSon
import fr.f4ioz.satcombo.sstv.SstvMeta
import fr.f4ioz.satcombo.station.PlansMontage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The SSTV options on SatMe-PC: « diapo manquante attendue » (said as the
 * activities' notices, and aloud when the announcements speak), the sound of
 * each SSTV pass and how long it is kept, the live reception quality, the
 * QSL picture and the ARISS award pack (downloads; nothing is sent).
 *
 * `/api/sstv-options`: GET the settings, the retention's plan, the last
 * notices; GET `/qualite`, `/qsl?nom=&dispo=&jpeg=`, `/ariss.zip?noms=`;
 * POST `/reglage?k=&v=`, `/retention` (applied now), `/garde?nom=&on=`.
 */
class OptionsSstvPc(
    private val station: StationPc,
    private val son: SonStation,
    private val classement: ClassementSstvPc,
    private val activites: () -> ActivitesPc?,
    private val dossier: File,
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    val options = OptionsSstv(station.stockage)
    private val fichierDites = File(dossier, "sstv-alertes-diapos.json")
    private val fichierGardes = File(dossier, "sstv-gardes.txt")
    /** The recordings made while a series' satellite was followed (the option or a campaign). */
    private val fichierSstv = File(dossier, "sstv-enregistrements.txt")
    private val journalRetention get() = File(son.dossierEnregistrements, "retention-sons.log")
    @Volatile private var dites: Set<String> = AlerteDiapo.litDites(runCatching { fichierDites.readText() }.getOrNull())
    /** A sentence said aloud (when the announcements are on), set by Main. */
    @Volatile var annonce: (String) -> Unit = {}

    // ------------------------------------------------------------------ the series

    @Volatile private var serieVue: Pair<Long, Int>? = null

    /** The catalogue number of the SSTV series under way now (kept 30 s); 0 none. */
    private fun serieEnCours(): Int {
        val t = horloge()
        serieVue?.let { (quand, n) -> if (t - quand < 30_000L) return n }
        val n = runCatching { classement.serie(t)?.takeIf { it.enCours(t) }?.catnum }.getOrNull() ?: 0
        serieVue = t to n
        return n
    }

    /** For [SonStation.enregistreSerie]: the option on, SSTV decoding on, the series' satellite followed. */
    fun enregistreSerie(): Boolean {
        if (!options.enregistrePassages || !son.sstvActif) return false
        val n = serieEnCours()
        return n != 0 && station.etat.value.suivi?.catalogNumber == n
    }

    // ------------------------------------------------------------------ running

    private var derniereRetention = 0L

    /** Every 30 s (off the page's thread): the notices due, the recording noted, the retention once an hour. */
    fun tic() {
        val t = horloge()
        runCatching { notesEnregistrement() }
        runCatching { alertes(t) }
        if (t - derniereRetention > 3_600_000L) {
            derniereRetention = t
            runCatching { if (options.reglagesRetention.actif) appliqueRetention("réglage") }
        }
    }

    /** The recording under way while the series' satellite is followed: an SSTV pass's, noted. */
    private fun notesEnregistrement() {
        val e = son.etat.value
        if (e.enregistrement.isBlank()) return
        val n = serieEnCours()
        if (n == 0 || station.etat.value.suivi?.catalogNumber != n) return
        val l = RetentionSon.litGardes(fichierSstv)
        if (e.enregistrement !in l) runCatching {
            fichierSstv.parentFile?.mkdirs()
            fichierSstv.writeText("# SatMe : enregistrements de passages SSTV\n" + (l + e.enregistrement).joinToString("\n") + "\n")
        }
    }

    @Synchronized private fun alertes(t: Long) {
        val a = activites() ?: return
        val r = options.reglagesAlerte
        if (!r.actif || !station.settings.activitesOn) return
        val p = classement.prevision(t = t) ?: return
        val dues = AlerteDiapo.dues(AlerteDiapo.alertes(p, r, t), dites, t)
        if (dues.isEmpty()) return
        for (d in dues) {
            val texte = AlerteDiapo.texte(d)
            a.signale("diapo", texte, "diapo:" + d.cle)
            runCatching { annonce(AlerteDiapo.titre(d)) }
        }
        dites = dites + dues.map { it.cle }
        runCatching { fichierDites.parentFile?.mkdirs(); fichierDites.writeText(AlerteDiapo.ecritDites(dites)) }
    }

    /** The next notices to come (the page shows them). */
    fun prochaines(t: Long = horloge()): List<AlerteDiapo.Alerte> {
        val p = runCatching { classement.prevision(t = t) }.getOrNull() ?: return emptyList()
        return AlerteDiapo.alertes(p, options.reglagesAlerte, t).filter { it.cle !in dites }.take(6)
    }

    // ------------------------------------------------------------------ retention

    private fun historique(): List<CampagneSstv.Trace> =
        runCatching { CampagneSstv.lit(File(dossier, "sstv-campagnes.json").readText()).historique }.getOrDefault(emptyList())

    fun planRetention(t: Long = horloge()): RetentionSon.Plan {
        val images = son.images()
        val liees = RetentionSon.liees(images)
        val sstv = liees.keys + historique().map { it.enregistrement }.filter { it.isNotBlank() } + RetentionSon.litGardes(fichierSstv)
        val planches = RetentionSon.planches(runCatching { Planche.Rangement(File(dossier, "planches")).modeles() }.getOrDefault(emptyList()))
        val gardes = RetentionSon.litGardes(fichierGardes) + listOf(son.etat.value.enregistrement).filter { it.isNotBlank() }
        val journal = if (options.retentionJournal) runCatching {
            JournalPassage.Rangement(File(dossier, "journal")).passages().flatMap { it.enregistrements }.toSet()
        }.getOrDefault(emptySet()) else emptySet()
        val fichiers = son.enregistrements().map { RetentionSon.Fichier(it.name, it.length(), it.lastModified()) }
        return RetentionSon.plan(options.reglagesRetention, fichiers, sstv, liees, planches, gardes, journal, t)
    }

    @Synchronized fun appliqueRetention(pourquoi: String): Int {
        val r = options.reglagesRetention
        if (!r.actif) return 0
        val p = planRetention()
        val raison = listOfNotNull(if (r.jours > 0) "plus de ${r.jours} jours" else null, if (r.maxMo > 0) "au-delà de ${r.maxMo} Mo" else null).joinToString(", ") + " ($pourquoi)"
        val n = RetentionSon.applique(p, son.dossierEnregistrements, journalRetention, raison, horloge())
        if (n > 0) println("rétention SSTV : $n enregistrement(s) supprimé(s), voir ${journalRetention.name}")
        return n
    }

    // ------------------------------------------------------------------ QSL and ARISS

    private fun campagnes(): List<CampagneSstv.Campagne> =
        runCatching { CampagneSstv.lit(File(dossier, "sstv-campagnes.json").readText()).campagnes }.getOrDefault(emptyList())

    private fun passagesAutour(s: SstvMeta.SstvShot): List<SatPass> {
        val norm = { x: String -> x.uppercase().replace(Regex("\\(.*?\\)"), "").replace(Regex("[^A-Z0-9]"), "") }
        val n = norm(s.satName)
        if (n.isEmpty() || s.timeMs <= 0) return emptyList()
        val sat = station.etat.value.satellites.firstOrNull { norm(it.name) == n } ?: station.etat.value.satellites.firstOrNull { norm(it.name).startsWith(n) }
            ?: return emptyList()
        return runCatching { station.passagesDe(sat.catalogNumber, s.timeMs - 2 * 3_600_000L, s.timeMs + 3_600_000L) }.getOrDefault(emptyList())
    }

    /** What the card says of [s]: its sidecar, its slide and mark from the gallery sorted, the series of its time. */
    private fun infos(s: SstvMeta.SstvShot): Triple<QslSstv.Infos, SatPass?, Int> {
        val acts = runCatching { activites()?.liste() }.getOrNull().orEmpty()
        val camps = campagnes()
        val serie = acts.firstOrNull { it.type == fr.f4ioz.satcombo.domain.ActivitesAVenir.Type.SSTV && s.timeMs in (it.debutMs - 3_600_000L)..(it.finMs + 3_600_000L) }?.titre
            ?: camps.firstOrNull { s.timeMs in (it.debutMs - 3_600_000L)..(it.finMs + 3_600_000L) }?.nom.orEmpty()
        // The series' groups first: their numbers include those the timetable estimates.
        val g = runCatching { classement.prevision()?.groupes?.firstOrNull { gr -> gr.images.any { it.fileName == s.fileName } } }.getOrNull()
            ?: runCatching { classement.etat().groupes.firstOrNull { gr -> gr.images.any { it.fileName == s.fileName } } }.getOrNull()
        val diapo = if (s.diapo > 0) s.diapo else g?.diapo ?: 0
        val note = g?.notes?.get(s.fileName) ?: -1
        val pas = passagesAutour(s)
        val st = station.settings
        val i = QslSstv.infos(s, st.callsign, station.etat.value.locator, pas, acts, camps, serie.take(60), diapo,
            if (s.diapos > 0) s.diapos else g?.total ?: 12, options.qslSatme)
        return Triple(i, QslSstv.passageDe(s, pas), note)
    }

    private fun image(nom: String): Pair<File, SstvMeta.SstvShot>? {
        val f = son.fichierImage(nom)?.takeIf { it.name.endsWith(".png") } ?: return null
        val s = son.images().firstOrNull { it.fileName == f.name } ?: SstvMeta.parseName(f.name)
        return f to s
    }

    /** The QSL card of a picture: its bytes and file name; null when it cannot be drawn. */
    fun qsl(nom: String, dispo: QslSstv.Disposition = options.qslDisposition, jpeg: Boolean = options.qslJpeg): Pair<ByteArray, String>? {
        val (f, s) = image(nom) ?: return null
        val b = QslRenduPc.rend(f, infos(s).first, dispo, jpeg) ?: return null
        return b to QslSstv.nomFichier(f.name, jpeg)
    }

    private fun poste(): String {
        val m = station.settings.rigModel
        return fr.f4ioz.satcombo.cat.ModeleIcom.entries.firstOrNull { it.id == m }?.libelle ?: m
    }

    /** The award pack of [noms] (the pictures in that order); null when none is a picture. */
    fun pack(noms: List<String>): Pair<ByteArray, String>? {
        val t = horloge()
        val l = noms.distinct().take(12).mapNotNull { n ->
            val (f, s) = image(n) ?: return@mapNotNull null
            // ARISS asks for the pictures as received: never a corrected or fused one.
            if (fr.f4ioz.satcombo.sstv.CorrectionsGalerie.estTraitee(f)) return@mapNotNull null
            val octets = runCatching { f.readBytes() }.getOrNull() ?: return@mapNotNull null
            val b = runCatching { javax.imageio.ImageIO.read(f) }.getOrNull() ?: return@mapNotNull null
            val (i, p, note) = infos(s)
            val jpeg = options.qslJpeg
            val qsl = runCatching { QslRenduPc.rend(b, i, options.qslDisposition, jpeg) }.getOrNull()
            PackAriss.Entree(s, octets, b.width, b.height, qsl, QslSstv.nomFichier(f.name, jpeg), i, p, note)
        }
        if (l.isEmpty()) return null
        val plans = runCatching { PlansMontage.lit(File(dossier, "montage.json").readText()) }.getOrNull()
        return PackAriss.zip(l, PackAriss.materiel(plans, poste()), t) to PackAriss.nomFichier(l.first().infos.indicatif, t)
    }

    /** The best copy of each slide of the series under way (numbered ones first, in slide order). */
    fun meilleuresDeLaSerie(): List<String> {
        val p = runCatching { classement.prevision() }.getOrNull() ?: return emptyList()
        return p.groupes.filter { it.diapo > 0 }.groupBy { it.diapo }.toSortedMap().values.map { gs ->
            gs.flatMap { g -> g.images.map { it.fileName to (g.notes[it.fileName] ?: -1) } }.maxByOrNull { it.second }!!.first
        }
    }

    // ------------------------------------------------------------------ the page

    private fun json(o: Any) = ReponseWeb("200 OK", "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))
    private fun refus(t: String) = json(JSONObject().put("ok", false).put("erreur", t))
    private fun fichier(b: ByteArray, nom: String, type: String) = ReponseWeb("200 OK", type, b, "Content-Disposition: attachment; filename=\"$nom\"\r\n")

    /** A picture deleted: its « à garder » mark taken off too. */
    fun oublieGarde(nom: String) { File(nom).name.takeIf { it.isNotBlank() }?.let { RetentionSon.marqueGarde(fichierGardes, it, false) } }

    fun sert(chemin: String, methode: String, p: (String) -> String): ReponseWeb {
        val route = chemin.removePrefix("/api/sstv-options")
        if (methode != "POST") return when (route) {
            "", "/" -> json(json())
            "/qualite" -> json(qualite())
            "/qsl" -> {
                val d = runCatching { QslSstv.Disposition.valueOf(p("dispo")) }.getOrDefault(options.qslDisposition)
                val jpeg = if (p("jpeg").isNotBlank()) p("jpeg") == "1" else options.qslJpeg
                val (b, n) = qsl(p("nom"), d, jpeg) ?: return refus("image introuvable")
                if (p("voir") == "1") ReponseWeb("200 OK", if (jpeg) "image/jpeg" else "image/png", b) else fichier(b, n, if (jpeg) "image/jpeg" else "image/png")
            }
            "/ariss.zip" -> {
                val noms = if (p("serie") == "1") meilleuresDeLaSerie() else p("noms").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val (b, n) = pack(noms) ?: return refus("aucune image")
                fichier(b, n, "application/zip")
            }
            else -> refus("inconnu")
        }
        return when (route) {
            "/reglage" -> {
                val v = p("v"); val on = v == "1"
                val o = options
                when (p("k")) {
                    "alerte" -> o.alerteDiapo = on
                    "avant" -> o.alerteAvantMin = v.toIntOrNull() ?: 15
                    "note" -> o.alerteNoteMin = v.toIntOrNull() ?: 60
                    "enreg" -> o.enregistrePassages = on
                    "jours" -> o.retentionJours = v.toIntOrNull() ?: 0
                    "mo" -> o.retentionMaxMo = v.toIntOrNull() ?: 0
                    "journal" -> o.retentionJournal = on
                    "redecode" -> o.redecodeFin = on
                    "dispo" -> o.qslDisposition = runCatching { QslSstv.Disposition.valueOf(v) }.getOrDefault(QslSstv.Disposition.BANDEAU)
                    "satme" -> o.qslSatme = on
                    "jpeg" -> o.qslJpeg = on
                    else -> return refus("réglage inconnu")
                }
                json(json())
            }
            "/retention" -> json(JSONObject().put("ok", true).put("supprimes", appliqueRetention("à la main")))
            "/garde" -> {
                val n = File(p("nom")).name
                if (n.isBlank()) return refus("nom")
                RetentionSon.marqueGarde(fichierGardes, n, p("on") == "1")
                json(JSONObject().put("ok", true).put("gardes", JSONArray(RetentionSon.litGardes(fichierGardes).toList())))
            }
            else -> refus("inconnu")
        }
    }

    fun qualite(): JSONObject {
        val q = son.sstv.qualite() ?: return JSONObject().put("ok", true).put("son", false)
        return JSONObject().put("ok", true).put("son", true).put("niveauDb", q.niveauDb ?: JSONObject.NULL).put("creteDb", q.creteDb ?: JSONObject.NULL)
            .put("ecretes", q.ecretes).put("niveau", q.niveau.name).put("image", q.image).put("mode", q.mode).put("lignes", q.lignes)
            .put("hauteur", q.hauteur).put("trouvees", q.trouvees).put("nettes", q.nettes).put("snr", q.snrDb ?: JSONObject.NULL)
            .put("snrSous", q.snrSousSeuil).put("qualites", JSONArray(q.qualites)).put("note", q.noteEstimee ?: JSONObject.NULL)
            .put("noteFinale", q.noteFinale ?: JSONObject.NULL)
    }

    fun json(): JSONObject {
        val o = options
        val t = horloge()
        val plan = runCatching { planRetention(t) }.getOrNull()
        return JSONObject().put("ok", true)
            .put("reglages", JSONObject().put("alerte", o.alerteDiapo).put("avant", o.alerteAvantMin).put("note", o.alerteNoteMin)
                .put("enreg", o.enregistrePassages).put("jours", o.retentionJours).put("mo", o.retentionMaxMo).put("journal", o.retentionJournal)
                .put("redecode", o.redecodeFin).put("dispo", o.qslDisposition.name).put("satme", o.qslSatme).put("jpeg", o.qslJpeg)
                .put("activites", station.settings.activitesOn).put("indicatif", station.settings.callsign).put("locator", station.etat.value.locator))
            .put("enregistreMaintenant", runCatching { enregistreSerie() }.getOrDefault(false))
            .put("prochaines", JSONArray(prochaines(t).map { a ->
                JSONObject().put("quand", a.quandMs).put("aos", a.passage.aosEpochMs).put("el", Math.round(a.passage.maxElevationDeg)).put("texte", AlerteDiapo.texte(a))
                    .put("diapos", JSONArray(a.diapos.map { it.diapo }))
            }))
            .put("retention", plan?.let { r ->
                JSONObject().put("sstv", r.sstv).put("octets", r.octetsSstv).put("supprimer", JSONArray(r.supprimer.map { it.nom }))
                    .put("octetsLiberes", r.octetsLiberes).put("proteges", r.proteges.size)
            } ?: JSONObject.NULL)
            .put("gardes", JSONArray(RetentionSon.litGardes(fichierGardes).toList()))
    }
}
