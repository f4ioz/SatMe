/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.datv.BalisesQo100
import fr.f4ioz.satcombo.datv.BandeLarge
import fr.f4ioz.satcombo.datv.Datv
import fr.f4ioz.satcombo.datv.HistoriqueBalises
import fr.f4ioz.satcombo.datv.ReglageDatv
import fr.f4ioz.satcombo.plateforme.Stockage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * « Balises QO-100 » (DATV tab; off unless chosen): QO-100's beacons watched
 * each second ([BalisesQo100]) — the wide band DATV beacon on the ground
 * station's spectrum ([SpectreLargePc], kept connected while this is on) and,
 * whenever the DATV receiver is on it, as received here (lock, MER, the
 * stream PID by PID, pictures made); the narrow band beacons as the SDR hears
 * them ([etroites]). A verdict for each, its history (a point every 5 min,
 * each change, [HistoriqueBalises.jours] days, in [fichier]), an alert when a
 * beacon goes off or its stream is damaged at the source while our
 * reception is good.
 *
 * With « contrôler l'image » (off unless chosen), the MiniTiouner — here or
 * on a SatMe-Node — is tuned to the beacon for [SONDE_MS] every
 * [periodeMin] minutes while nobody uses it, its settings put back after.
 * Receive only: nothing is ever sent.
 */
class BalisesQo100Pc(
    private val stockage: Stockage,
    private val datv: DatvPc,
    private val large: SpectreLargePc,
    private val fichier: File?,
    private val horloge: () -> Long = System::currentTimeMillis,
    /** Starts the receiver with these settings (null when started, else why not). */
    private val recois: (ReglageDatv) -> String? = { datv.demarre(it) },
    /** Stops the receiver. */
    private val arrete: () -> Unit = { datv.arrete() },
) {
    /** The narrow band beacons as the SDR hears them now (key → measure, null not heard); null: no SDR on the transponder. */
    @Volatile var etroites: () -> Map<String, BalisesQo100.Ecoute?>? = { null }
    /** Something else holds the receiver (the scan moving, a transmission…): no check now. */
    @Volatile var occupe: () -> Boolean = { false }

    var on: Boolean
        get() = stockage.getBoolean("balises_on", false)
        set(v) { stockage.edit().putBoolean("balises_on", v).apply(); if (!v) abandonneSonde(true) }
    /** The beacon's picture checked with the free MiniTiouner (off unless chosen). */
    var controle: Boolean
        get() = stockage.getBoolean("balises_controle", false)
        set(v) { stockage.edit().putBoolean("balises_controle", v).apply(); if (!v) abandonneSonde(true) }
    var periodeMin: Int
        get() = stockage.getInt("balises_periode_min", 30).coerceIn(10, 240)
        set(v) { stockage.edit().putInt("balises_periode_min", v.coerceIn(10, 240)).apply() }
    var jours: Int
        get() = stockage.getInt("balises_jours", 7).coerceIn(1, 7)
        set(v) { stockage.edit().putInt("balises_jours", v.coerceIn(1, 7)).apply(); historique.jours = v.coerceIn(1, 7) }

    val historique = HistoriqueBalises(jours = stockage.getInt("balises_jours", 7).coerceIn(1, 7)).also { h ->
        runCatching { fichier?.takeIf { it.isFile }?.readText() }.getOrNull()?.let { h.reprends(it, horloge()) }
    }
    @Volatile private var gardeMs = 0L

    // ------------------------------------------------------------------ what is seen now

    @Volatile var avisLarge: BalisesQo100.Avis? = null; private set
    @Volatile var avisEtroites: BalisesQo100.Avis? = null; private set
    @Volatile private var spectre: Boolean? = null
    @Volatile private var spectreDb: Double? = null
    @Volatile private var reception: BalisesQo100.Reception? = null
    @Volatile private var ecoutes: Map<String, BalisesQo100.Ecoute?>? = null
    /** The quality seconds while on the beacon (10 s back), for the stream's window. */
    private val qualites = ArrayDeque<Pair<Long, JSONObject>>()
    private var surBaliseMs = 0L

    /** The alerts since the start, for the page's toasts. */
    class Alerte(val n: Long, val t: Long, val texte: String, val grave: Boolean)
    private val alertes = ArrayDeque<Alerte>()
    @Volatile private var numero = 0L
    /** The last alert said a beacon was at fault: its return is said too. */
    private val enDefaut = HashSet<String>()

    /** The receiver on the beacon now. */
    fun surBalise(): Boolean = datv.etat.actif && Math.abs(datv.reglage.cielKhz - Datv.BALISE_KHZ) <= 100

    /** Each second, while on. */
    @Synchronized
    fun tic() {
        val t = horloge()
        if (!on) { avisLarge = null; avisEtroites = null; return }
        // The ground station's spectrum: the beacon on it, its level over the noise.
        if (large.on && large.frais()) {
            val b = large.signaux.firstOrNull { it.balise }
            spectre = b != null
            spectreDb = b?.let { Math.round((it.niveau - BandeLarge.BRUIT) / BandeLarge.UNITES_PAR_DB * 10) / 10.0 }
        } else { spectre = null; spectreDb = null }
        // Our receiver on the beacon: its figures over the last 10 s.
        reception = if (surBalise()) {
            if (surBaliseMs == 0L) { surBaliseMs = t; qualites.clear() }
            val e = datv.etat
            val q = datv.qualite()
            if (q != null && qualites.lastOrNull()?.second !== q) { qualites.addLast(t to q); while (qualites.isNotEmpty() && t - qualites.first().first > 11_000L) qualites.removeFirst() }
            val avant = qualites.firstOrNull()?.second?.takeIf { qualites.size > 1 }
            val flux = q?.let { BalisesQo100.flux(avant, it, if (it.isNull("imagesS")) null else it.optDouble("imagesS")) } ?: BalisesQo100.Flux()
            val fec = q?.opt("fec")?.let { it as? Boolean }
            val ratio = q?.takeIf { !it.isNull("ratio") }?.optDouble("ratio")?.takeIf { !it.isNaN() }
            BalisesQo100.Reception(t - surBaliseMs, e.verrouille, e.merDb?.takeIf { e.verrouille }, datv.marge(e), fec, ratio, flux)
        } else { surBaliseMs = 0L; qualites.clear(); null }
        ecoutes = lisse(t, runCatching { etroites() }.getOrNull())
        val al = BalisesQo100.large(BalisesQo100.Large(spectre, spectreDb, reception, ecoutes?.get("mediane")?.db))
        val ae = BalisesQo100.etroites(ecoutes != null, ecoutes.orEmpty())
        avisLarge = al; avisEtroites = ae
        historique.avis(t, "large", al)?.let { alerte(it) }
        historique.avis(t, "etroites", ae)?.let { alerte(it) }
        historique.mesure(t, reception?.merDb, spectreDb, ecoutes?.get("mediane")?.db)
        if (t - gardeMs >= 5 * 60_000L) garde(t)
        sonde(t)
    }

    /** Each beacon's last measure: a keyed CW beacon is silent between its letters, still heard for [ENTENDUE_MS]. */
    private val entendues = HashMap<String, Pair<Long, BalisesQo100.Ecoute>>()

    private fun lisse(t: Long, l: Map<String, BalisesQo100.Ecoute?>?): Map<String, BalisesQo100.Ecoute?>? {
        if (l == null) { entendues.clear(); return null }
        return l.mapValues { (k, m) ->
            if (m != null) { entendues[k] = t to m; m } else entendues[k]?.takeIf { t - it.first <= ENTENDUE_MS }?.second
        }
    }

    private fun alerte(e: HistoriqueBalises.Evenement) {
        val quoi = if (e.quoi == "large") "Balise DATV QO-100" else "Balises bande étroite QO-100"
        val defaut = e.cause == BalisesQo100.Cause.SOURCE && e.statut.gravite >= 2
        val texte = when {
            defaut -> "$quoi : " + e.texte
            e.statut == BalisesQo100.Statut.OK && e.quoi in enDefaut -> "$quoi revenue : " + e.texte
            else -> null
        }
        if (defaut) enDefaut += e.quoi else if (e.statut == BalisesQo100.Statut.OK) enDefaut -= e.quoi
        garde(e.t)
        if (texte == null) return
        synchronized(alertes) { alertes.addLast(Alerte(++numero, e.t, texte, defaut)); while (alertes.size > 20) alertes.removeFirst() }
        println("balises QO-100 : $texte")
    }

    private fun garde(t: Long) {
        gardeMs = t
        val f = fichier ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(historique.texte())
            java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
    }

    // ------------------------------------------------------------------ the check with the free receiver

    /** A check under way: since when, the settings to put back. */
    private class Sonde(val debutMs: Long, val avant: ReglageDatv)
    @Volatile private var enCours: Sonde? = null
    @Volatile private var derniereSondeMs = 0L
    @Volatile var sondeErreur = ""; private set

    /** True while the beacon's check holds the receiver (the scan leaves it alone). */
    fun sondeEnCours(): Boolean = enCours != null

    private fun sonde(t: Long) {
        val s = enCours
        if (s != null) {
            // Moved or stopped by someone else: theirs now.
            if (!surBalise()) { enCours = null; if (!datv.etat.actif) datv.retiens(s.avant); return }
            if (t - s.debutMs >= SONDE_MS) { enCours = null; arrete(); datv.retiens(s.avant) }
            return
        }
        if (!controle || datv.etat.actif || datv.recepteur != Datv.MINITIOUNER || occupe()) return
        if (derniereSondeMs == 0L) derniereSondeMs = t - periodeMin * 60_000L + PREMIERE_SONDE_MS
        if (t - derniereSondeMs < periodeMin * 60_000L) return
        derniereSondeMs = t
        val avant = datv.reglage
        val e = recois(avant.copy(cielKhz = Datv.BALISE_KHZ, srKs = BalisesQo100.SR_KS))
        if (e != null) { sondeErreur = e; datv.retiens(avant); return }
        sondeErreur = ""
        enCours = Sonde(t, avant)
    }

    /** The operator tuned the receiver: the check (if any) gives way, nothing put back over their choice. */
    fun manuel() { enCours = null }

    /** The operator stopped the receiver: a check under way ends, the settings it found put back. */
    fun arretDemande() { val s = enCours ?: return; enCours = null; datv.retiens(s.avant) }

    private fun abandonneSonde(remet: Boolean) {
        val s = enCours ?: return
        enCours = null
        if (remet && surBalise()) { arrete(); datv.retiens(s.avant) }
    }

    // ------------------------------------------------------------------ the page, the site

    fun alertesDepuis(n: Long): List<Alerte> = synchronized(alertes) { alertes.filter { it.n > n } }

    /** For the page; [court]: the verdicts and the alerts only (a look from another tab). */
    fun json(depuis: Long = 0L, court: Boolean = false): JSONObject {
        val t = horloge()
        fun nul(x: Any?) = x ?: JSONObject.NULL
        fun avis(a: BalisesQo100.Avis?, quoi: String) = JSONObject().put("statut", a?.statut?.cle ?: "inconnu").put("libelle", a?.statut?.libelle ?: "")
            .put("cause", a?.cause?.cle ?: "").put("texte", a?.texte ?: "").put("depuis", nul(historique.courant(quoi)?.second))
        val o = JSONObject().put("ok", true).put("on", on).put("controle", controle).put("periode", periodeMin).put("jours", jours)
            .put("spectreOn", large.on).put("minitiouner", datv.recepteur == Datv.MINITIOUNER)
        val l = avis(avisLarge, "large").put("spectre", nul(spectre)).put("spectreDb", nul(spectreDb))
        reception?.let { r ->
            l.put("reception", JSONObject().put("depuis", r.depuisMs / 1000).put("verrouille", r.verrouille).put("mer", nul(r.merDb)).put("marge", nul(r.margeDb))
                .put("fec", nul(r.fecEchec)).put("ratio", nul(r.ratio)).put("tauxEs", r.flux.tauxEs).put("ccEs", r.flux.ccEs).put("paquetsEs", r.flux.paquetsEs)
                .put("ccAutres", r.flux.ccAutres).put("paquetsAutres", r.flux.paquetsAutres).put("tei", r.flux.tei).put("videoMin", r.flux.videoMin)
                .put("images", nul(r.flux.imagesParS)))
        } ?: l.put("reception", JSONObject.NULL)
        o.put("large", l)
        val e = avis(avisEtroites, "etroites")
        val ec = ecoutes
        e.put("sdr", ec != null).put("liste", JSONArray(BalisesQo100.ETROITES.map { b ->
            val m = ec?.get(b.cle)
            JSONObject().put("cle", b.cle).put("nom", b.nom).put("hz", b.hz).put("db", nul(m?.db?.let { Math.round(it * 10) / 10.0 }))
                .put("ecart", nul(m?.ecartHz?.let { Math.round(it) }))
        }))
        o.put("etroites", e)
        o.put("sonde", JSONObject().put("enCours", enCours != null).put("reste", enCours?.let { maxOf(0L, (SONDE_MS - (t - it.debutMs)) / 1000) } ?: 0)
            .put("prochaine", if (controle && derniereSondeMs > 0) derniereSondeMs + periodeMin * 60_000L else 0L).put("erreur", sondeErreur))
        if (!court) o.put("evenements", JSONArray(historique.evenements().reversed().take(60).map {
            JSONObject().put("t", it.t).put("quoi", it.quoi).put("statut", it.statut.cle).put("cause", it.cause.cle).put("texte", it.texte)
        }))
        if (!court) o.put("points", JSONArray(historique.points().map { p ->
            JSONArray().put(p.t).put(p.large.cle).put(p.etroites.cle).put(nul(p.mer)).put(nul(p.sol)).put(nul(p.etroite))
        }))
        o.put("alertes", JSONArray(alertesDepuis(depuis).map { JSONObject().put("n", it.n).put("t", it.t).put("texte", it.texte).put("grave", it.grave) }))
            .put("n", numero)
        return o
    }

    fun action(q: Map<String, String>): JSONObject {
        q["on"]?.let { on = it == "1" }
        q["controle"]?.let { controle = it == "1"; if (controle) derniereSondeMs = 0L }
        q["periode"]?.trim()?.toIntOrNull()?.let { periodeMin = it }
        q["jours"]?.trim()?.toIntOrNull()?.let { jours = it }
        if (q["oublie"] == "1") synchronized(this) { historique.reprends("{}", horloge()); garde(horloge()) }
        return JSONObject().put("ok", true)
    }

    /**
     * The beacons for the site (« Publication web › En direct › Balises QO-100 », in `direct` as `balises`): only
     * each verdict, its cause, its words, since when, and the beacon's MER and level when measured. Null while off.
     */
    fun siteJson(): JSONObject? {
        if (!on) return null
        return siteJson(avisLarge, historique.courant("large")?.second, reception?.merDb, spectreDb, avisEtroites, historique.courant("etroites")?.second)
    }

    companion object {
        /** A narrow band beacon heard within this still counts as heard. */
        const val ENTENDUE_MS = 30_000L
        /** How long a check holds the receiver. */
        const val SONDE_MS = 60_000L
        /** The first check this long after the option is on (not at once on starting SatMe-PC). */
        const val PREMIERE_SONDE_MS = 2 * 60_000L

        /** The keys [siteJson] may write, each level by itself: what a test checks, what the documentation lists. */
        val CLES_SITE = mapOf("" to setOf("large", "etroites"), "large" to setOf("statut", "cause", "texte", "depuis", "mer", "niveauDb"),
            "etroites" to setOf("statut", "cause", "texte", "depuis"))

        fun siteJson(large: BalisesQo100.Avis?, depuisLarge: Long?, mer: Double?, niveauDb: Double?, etroites: BalisesQo100.Avis?, depuisEtroites: Long?): JSONObject {
            fun avis(a: BalisesQo100.Avis, depuis: Long?) = JSONObject().put("statut", a.statut.cle).put("cause", a.cause.cle).put("texte", a.texte.take(200))
                .also { o -> depuis?.let { o.put("depuis", it) } }
            val o = JSONObject()
            large?.let { a -> o.put("large", avis(a, depuisLarge).also { j -> mer?.let { j.put("mer", Math.round(it * 10) / 10.0) }; niveauDb?.let { j.put("niveauDb", Math.round(it * 10) / 10.0) } }) }
            etroites?.takeIf { it.statut != BalisesQo100.Statut.INCONNU }?.let { o.put("etroites", avis(it, depuisEtroites)) }
            return o
        }
    }
}
