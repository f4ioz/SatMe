/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.datv.EtatDatv
import fr.f4ioz.satcombo.datv.ReglageDatv
import fr.f4ioz.satcombo.plateforme.Stockage
import org.json.JSONArray
import org.json.JSONObject
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * « Réglages › Envoyer vers un site web › Direct »: what SatMe-PC sends live to
 * the owner's site (the types `direct`, `direct-spectre`, `direct-datv-image`,
 * `direct-sstv-image`, `direct-efface` of the publication API, docs/publication-web.md;
 * to a folder, `direct/direct.json` and the pictures), each part switched on by itself and off by default —
 * **Auto-pilote** (the next campaign pass, the one under way, its next
 * picture, the last picture received; during a pass, the picture arriving
 * every few seconds, which the site's live block shows instead of QO-100), **Spectre QO-100** (the narrow band
 * as the SDR sees it, one compact line a second), **Image DATV** (a small
 * JPEG every few seconds while the receiver is locked, with its figures),
 * **Satellites favoris** (the list of the satellites marked ★, type `favoris`: as soon as it changes, else once an
 * hour; to a folder, `favoris.json`; a receiver that does not know the type — 400, 404 or 415 — is left alone for
 * the day, without an error), **Station** (type `direct-station`: the satellite followed, where the antennas
 * really point, the receivers and the rig in use, as [stationJson] builds it; when it changes, at most every
 * [periode] seconds, and every [BATTEMENT_STATION_MS] unchanged; to a folder, `direct/station.json`), **FT8/FT4**
 * (type `direct-ft8`: each slot once decoded, its messages and a strip of the waterfall, [Ft8SitePc]; to a folder,
 * `direct/ft8.json`).
 *
 * SatMe-PC pushes; the site never connects to the station. Nothing is
 * queued: what the site does not take is dropped and the next try waits
 * (30 s, 1 min… 5 min; 2 min after a refusal, 10 min after a wrong token).
 * What goes is built here from a short list of names and figures: never an
 * address, a pairing code, a password, a key or any other setting.
 */
class DirectSitePc(
    private val stockage: Stockage,
    private val site: SitePc,
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    /** One part: its name in the API and the settings, its label, its period (s) by default and its bounds. */
    enum class Canal(val nom: String, val libelle: String, val periodeDefaut: Int, val min: Int, val max: Int) {
        PILOTE("pilote", "Auto-pilote", 10, 10, 10),
        SPECTRE("spectre", "Spectre QO-100", 1, 1, 10),
        DATV("datv", "Image DATV", 5, 3, 30),
        /** The favourites: on change, else once an hour ([PERIODE_FAVORIS_MS]). */
        FAVORIS("favoris", "Satellites favoris", 3600, 3600, 3600),
        /** The station now: on change, at most every [periode] s, else every [BATTEMENT_STATION_MS]. */
        STATION("station", "Station", 2, 2, 10),
        /** The awards followed (« Agenda › Diplômes »): on change, else once a day ([PERIODE_DIPLOMES_MS]). */
        DIPLOMES("diplomes", "Diplômes", 86400, 86400, 86400),
        /** FT8 / FT4: each slot once decoded ([Ft8SitePc], type `direct-ft8`). */
        FT8("ft8", "FT8/FT4", 15, 15, 15);

        companion object { fun de(n: String) = values().firstOrNull { it.nom == n } }
    }

    /** The DATV receiver now, as [DatvPc] has it. */
    class Datv(val etat: EtatDatv, val reglage: ReglageDatv, val marge: Double?, val image: ByteArray?)

    /** The SDR's panorama while on QO-100: its bins (dB) and its axis (sky Hz; a negative width is turned over). */
    class Panorama(val db: FloatArray, val centreHz: Double, val largeurHz: Double)

    // Set by Main.
    /** The auto-pilot's state for the site ([AutoPiloteSstvPc.etatSite]). */
    @Volatile var pilote: () -> JSONObject? = { null }
    /**
     * The SSTV picture of the pass under way (since AOS, ms): the one arriving, else the last received; null
     * when none. Sent with « Auto-pilote », every [PERIODE_SSTV_MS] while the pass lasts.
     */
    @Volatile var imageSstv: (Long) -> ByteArray? = { null }
    /** The last SSTV picture received on the air: satellite, UTC ms, mode. */
    @Volatile var derniereImage: () -> Triple<String, Long, String>? = { null }
    @Volatile var datv: () -> Datv? = { null }
    @Volatile var panorama: () -> Panorama? = { null }
    /** Keeps the SDR's panorama worked out (it runs only while asked). */
    @Volatile var demandePanorama: () -> Unit = {}
    /** The satellites marked ★ (name, NORAD number), null while not known yet. */
    @Volatile var favoris: () -> List<Pair<String, Int>>? = { null }
    /** The awards as the site may show them ([DiplomesSitePc.json]: `{"diplomes":[…]}`), null while « Diplômes » is off. */
    @Volatile var diplomes: () -> JSONObject? = { null }
    /** The station now ([Station]), null when it cannot be told. */
    @Volatile var station: () -> Station? = { null }
    /** FT8 / FT4's slots and strips ([Ft8SitePc]), null when not set. */
    @Volatile var ft8: Ft8SitePc? = null

    /**
     * The station as the site's live page shows it: the satellite it works ([Sat]), the antennas ([Rotor], and
     * the fixed dish's pointing for QO-100), the receivers and the rig ([Sdr], [DatvCourt], [Poste]), whether
     * something transmits, the mode. Names, angles, frequencies only: no port, address or path has a place here.
     */
    class Station(val sat: Sat? = null, val rotor: Rotor? = null, val parabole: Pair<Double, Double>? = null, val sdr: Sdr? = null,
                  val datv: DatvCourt? = null, val poste: Poste? = null, val enEmission: Boolean = false, val mode: String? = null)
    /** [geo]: geostationary (no AOS/LOS); az/el/km/km·s⁻¹ from the station; the next pass or the one under way. */
    class Sat(val nom: String, val norad: Int, val geo: Boolean, val az: Double? = null, val el: Double? = null, val distanceKm: Double? = null,
              val vitesseKmS: Double? = null, val aos: Long? = null, val los: Long? = null, val elMax: Double? = null,
              val emetteurs: List<Emetteur> = emptyList(), val choisi: String? = null)
    class Emetteur(val description: String, val mode: String?, val descenteHz: Long?, val monteeHz: Long?)
    /** The mast: read back ([az], [el]), aimed at ([cibleAz], [cibleEl]). */
    class Rotor(val connecte: Boolean, val az: Double?, val el: Double?, val cibleAz: Double?, val cibleEl: Double?, val enMouvement: Boolean,
                val horsPortee: Boolean, val azSeul: Boolean)
    /** [type]: the receiver's public name (« RTL-SDR », « ADALM-PLUTO »…); [frequenceHz]: the sky frequency heard. */
    class Sdr(val actif: Boolean, val type: String, val frequenceHz: Long?)
    class DatvCourt(val actif: Boolean, val verrouille: Boolean, val mer: Double?, val marge: Double?, val service: String?, val cielKhz: Long?)
    /** [modele]: the rig's model name only (« IC-9700 »); its sky frequencies and mode read back. */
    class Poste(val modele: String, val connecte: Boolean, val descenteHz: Long?, val monteeHz: Long?, val mode: String?)

    fun on(c: Canal): Boolean = stockage.getBoolean("site_direct_" + c.nom, false)
    fun periode(c: Canal): Int = stockage.getInt("site_direct_" + c.nom + "_s", c.periodeDefaut).coerceIn(c.min, c.max)

    private class Suivi { var prochainMs = 0L; var delaiMs = 0L; var okMs = 0L; var erreur = ""; var envoye = false; var dernier = ""; var dernierMs = 0L
        /** The site answered that it does not know the type (400, 404, 415): not tried again before [prochainMs]. */
        var nonPris = false }
    private val suivis = Canal.values().associateWith { Suivi() }
    private val suiviSstv = Suivi()
    /** The station's payload sent last (without its time), for « on change ». */
    private var stationTexte = ""
    /** The pass under way the auto-pilot said last (AOS, LOS), for its pictures. */
    @Volatile private var passage: Pair<Long, Long>? = null
    @Volatile private var imageEnvoyee: ByteArray? = null

    // ------------------------------------------------------------------ the loop

    @Volatile private var fil: Thread? = null

    fun demarre() {
        if (fil != null) return
        fil = Thread({ while (true) { runCatching { tic() }; Thread.sleep(1000L) } }, "satme-site-direct").apply { isDaemon = true; start() }
    }

    /** Once a second: each part on and due goes (nothing at all while « Publication web » is switched off). */
    fun tic() {
        if (!site.actif) return
        val t = horloge()
        // The waterfall's strip of each slot kept while listening (the live and the passes published later).
        ft8?.let { f -> runCatching { f.capture(t) } }
        for (c in Canal.values()) {
            val s = suivis.getValue(c)
            if (!on(c)) { s.envoye = false; continue }
            if (c == Canal.SPECTRE) runCatching { demandePanorama() }
            if (c == Canal.FAVORIS) { runCatching { favoris(s, t) }.onFailure { e -> echec(s, t, 0, e.message ?: e.javaClass.simpleName) }; continue }
            if (c == Canal.STATION) { runCatching { station(s, t) }.onFailure { e -> echec(s, t, 0, e.message ?: e.javaClass.simpleName) }; continue }
            if (c == Canal.DIPLOMES) { runCatching { diplomes(s, t) }.onFailure { e -> echec(s, t, 0, e.message ?: e.javaClass.simpleName) }; continue }
            if (c == Canal.FT8) { runCatching { ft8(s, t) }.onFailure { e -> echec(s, t, 0, e.message ?: e.javaClass.simpleName) }; continue }
            if (t < s.prochainMs) continue
            s.prochainMs = t + periode(c) * 1000L
            runCatching { envoie(c, s, t) }.onFailure { e -> echec(s, t, 0, e.message ?: e.javaClass.simpleName) }
        }
        // The pass's picture, behind « Auto-pilote »: while the pass lasts (and a minute after LOS for the last one).
        val p = passage
        if (on(Canal.PILOTE) && p != null && t in p.first..(p.second + 60_000L) && t >= suiviSstv.prochainMs) {
            suiviSstv.prochainMs = t + PERIODE_SSTV_MS
            runCatching { imageSstv(p.first) }.getOrNull()?.let { b ->
                val cle = java.util.Arrays.hashCode(b)
                if (cle.toString() == suiviSstv.dernier) return
                val j = jpegReduit(b) ?: return
                val r = runCatching { site.envoieDirect("direct-sstv-image", null, j) }.getOrElse { echec(suiviSstv, t, 0, it.message ?: "erreur"); null } ?: return
                if (r.first in 200..299) { suiviSstv.dernier = cle.toString(); suiviSstv.okMs = t } else echec(suiviSstv, t, r.first, raison(r.second))
            }
        }
    }

    private fun envoie(c: Canal, s: Suivi, t: Long) {
        when (c) {
            Canal.PILOTE -> {
                val p = pilote()?.let { pilote(it, derniereImage(), t) }
                passage = p?.optJSONObject("enCours")?.takeIf { it.optString("phase") == "EN_COURS" }?.let { it.optLong("aos") to it.optLong("los") } ?: passage?.takeIf { t <= it.second + 60_000L }
                // Unchanged and sent lately: once a minute is enough to stay alive on the site.
                val texte = p?.toString().orEmpty()
                if (p == null && !s.envoye) return
                if (texte == s.dernier && t - s.dernierMs < 60_000L) return
                poste(s, t, "direct", JSONObject().put("t", t).put("pilote", p ?: JSONObject.NULL))?.let { if (it) { s.dernier = texte; s.dernierMs = t; s.envoye = p != null } }
            }
            Canal.SPECTRE -> {
                val pan = panorama() ?: return
                val l = ligneSpectre(pan.db, pan.centreHz, pan.largeurHz) ?: return
                poste(s, t, "direct-spectre", l)
            }
            // Their own pace (tic).
            Canal.FAVORIS -> favoris(s, t)
            Canal.STATION -> station(s, t)
            Canal.DIPLOMES -> diplomes(s, t)
            Canal.FT8 -> ft8(s, t)
            Canal.DATV -> {
                val d = datv()
                if (d == null || !d.etat.actif) {
                    if (s.envoye) poste(s, t, "direct", JSONObject().put("t", t).put("datv", JSONObject.NULL))?.let { if (it) s.envoye = false }
                    return
                }
                if (poste(s, t, "direct", JSONObject().put("t", t).put("datv", datv(d.etat, d.reglage, d.marge))) != true) return
                s.envoye = true
                val img = d.image
                if (d.etat.verrouille && img != null && img !== imageEnvoyee) {
                    val j = jpegReduit(img) ?: return
                    val r = site.envoieDirect("direct-datv-image", null, j) ?: return
                    if (r.first in 200..299) imageEnvoyee = img else echec(s, t, r.first, raison(r.second))
                }
            }
        }
    }

    /**
     * The favourites: sent when the list changed since the last one the site took, else once every [PERIODE_FAVORIS_MS];
     * only once the target is ready. A site that does not know the type is left alone for a day.
     */
    private fun favoris(s: Suivi, t: Long) {
        if (t < s.prochainMs || !site.configure) return
        val l = favoris() ?: return
        val o = favorisJson(l, t)
        val texte = o.getJSONArray("satellites").toString()
        if (texte == s.dernier && t - s.dernierMs < PERIODE_FAVORIS_MS) return
        val r = site.envoieDirect("favoris", o) ?: run { s.prochainMs = t + 60_000L; return }
        when {
            r.first in 200..299 -> { s.okMs = t; s.erreur = ""; s.delaiMs = 0L; s.nonPris = false; s.dernier = texte; s.dernierMs = t }
            r.first == 400 || r.first == 404 || r.first == 415 -> { s.nonPris = true; s.erreur = ""; s.prochainMs = t + NON_PRIS_MS }
            else -> echec(s, t, r.first, raison(r.second))
        }
    }

    /**
     * The awards: sent when they changed since the last list the site took, else once every [PERIODE_DIPLOMES_MS]
     * (only once the target is ready); looked at once a minute. A site that does not know the type is left alone for a day.
     */
    private fun diplomes(s: Suivi, t: Long) {
        if (t < s.prochainMs || !site.configure) return
        s.prochainMs = t + 60_000L
        val o = diplomes() ?: return
        val texte = o.toString()
        if (texte == s.dernier && t - s.dernierMs < PERIODE_DIPLOMES_MS) return
        val r = site.envoieDirect("diplomes", JSONObject(texte).put("t", t)) ?: return
        when {
            r.first in 200..299 -> { s.okMs = t; s.erreur = ""; s.delaiMs = 0L; s.nonPris = false; s.dernier = texte; s.dernierMs = t }
            r.first == 400 || r.first == 404 || r.first == 415 -> { s.nonPris = true; s.erreur = ""; s.prochainMs = t + NON_PRIS_MS }
            else -> echec(s, t, r.first, raison(r.second))
        }
    }

    /**
     * The station: looked at every [periode] seconds, sent when it changed (its time aside) or after
     * [BATTEMENT_STATION_MS] unchanged, so the site knows it is still there. A site that does not know the type is
     * asked again after [NON_PRIS_STATION_MS].
     */
    private fun station(s: Suivi, t: Long) {
        if (t < s.prochainMs) return
        s.prochainMs = t + periode(Canal.STATION) * 1000L
        val st = station() ?: return
        val o = stationJson(st)
        val texte = o.toString()
        if (texte == stationTexte && s.envoye && t - s.dernierMs < BATTEMENT_STATION_MS) return
        val r = site.envoieDirect("direct-station", JSONObject(texte).put("t", t)) ?: run { s.erreur = "site non réglé"; return }
        when {
            r.first in 200..299 -> { s.okMs = t; s.erreur = ""; s.delaiMs = 0L; s.nonPris = false; s.envoye = true; s.dernierMs = t; stationTexte = texte }
            r.first == 400 || r.first == 404 || r.first == 415 -> { s.nonPris = true; s.erreur = ""; s.prochainMs = t + NON_PRIS_STATION_MS }
            else -> echec(s, t, r.first, raison(r.second))
        }
    }

    /**
     * FT8 / FT4: each slot once over and decoded ([Ft8SitePc.prete]), sent once (dropped when the site does not take
     * it: live data). A site that does not know `direct-ft8` is asked again after [NON_PRIS_STATION_MS].
     */
    private fun ft8(s: Suivi, t: Long) {
        val f = ft8 ?: return
        if (t < s.prochainMs) return
        val o = f.prete(t) ?: return
        f.envoyee(o.optLong("t"))
        val r = site.envoieDirect("direct-ft8", o) ?: run { s.erreur = "site non réglé"; return }
        when {
            r.first in 200..299 -> { s.okMs = t; s.erreur = ""; s.delaiMs = 0L; s.nonPris = false; s.envoye = true }
            r.first == 400 || r.first == 404 || r.first == 415 -> { s.nonPris = true; s.erreur = ""; s.prochainMs = t + NON_PRIS_STATION_MS }
            else -> echec(s, t, r.first, raison(r.second))
        }
    }

    /** Sends [o] as [type]: true when taken, false when not (the next try put off), null when the target is not set. */
    private fun poste(s: Suivi, t: Long, type: String, o: JSONObject): Boolean? {
        val r = site.envoieDirect(type, o) ?: run { s.erreur = "site non réglé"; return null }
        if (r.first in 200..299) { s.okMs = t; s.erreur = ""; s.delaiMs = 0L; return true }
        echec(s, t, r.first, raison(r.second))
        return false
    }

    private fun echec(s: Suivi, t: Long, code: Int, texte: String) {
        when (code) {
            401 -> { s.erreur = "jeton refusé par le site"; s.prochainMs = t + 10 * 60_000L }
            403 -> { s.erreur = "refusé par le site : " + texte; s.prochainMs = t + 2 * 60_000L }
            413, 415, 400 -> { s.erreur = "HTTP $code : $texte"; s.prochainMs = t + 60_000L }
            else -> {
                s.delaiMs = (if (s.delaiMs == 0L) 30_000L else s.delaiMs * 2).coerceAtMost(5 * 60_000L)
                s.erreur = (if (code == 0) "pas de réponse : " else "HTTP $code : ") + texte
                s.prochainMs = t + s.delaiMs
            }
        }
    }

    // ------------------------------------------------------------------ the page

    fun json(): JSONObject = JSONObject().put("canaux", JSONArray(Canal.values().map { c ->
        val s = suivis.getValue(c)
        JSONObject().put("nom", c.nom).put("libelle", c.libelle).put("on", on(c)).put("periode", periode(c)).put("min", c.min).put("max", c.max)
            .put("ok", s.okMs).put("erreur", if (on(c)) s.erreur else "")
            .also { o -> if (c == Canal.FAVORIS) o.put("cadence", "à chaque changement, sinon 1 h").put("nonPris", on(c) && s.nonPris) }
            .also { o -> if (c == Canal.STATION) o.put("nonPris", on(c) && s.nonPris) }
            .also { o -> if (c == Canal.DIPLOMES) o.put("cadence", "à chaque changement, sinon 1 fois par jour").put("nonPris", on(c) && s.nonPris) }
            .also { o -> if (c == Canal.FT8) o.put("cadence", "chaque créneau décodé (15 s, FT4 7,5 s)").put("nonPris", on(c) && s.nonPris) }
    }))

    /** `api/site/direct`: `on_<part>=0|1`, `periode_<part>=<s>`. */
    fun regle(p: (String) -> String) {
        val e = stockage.edit()
        for (c in Canal.values()) {
            when (p("on_" + c.nom)) { "1" -> e.putBoolean("site_direct_" + c.nom, true); "0" -> e.putBoolean("site_direct_" + c.nom, false) }
            p("periode_" + c.nom).toIntOrNull()?.let { e.putInt("site_direct_" + c.nom + "_s", it.coerceIn(c.min, c.max)) }
        }
        e.apply()
        // A change: try at once.
        suivis.values.forEach { it.prochainMs = 0L; it.delaiMs = 0L; it.erreur = ""; it.nonPris = false }
        // The favourites sent again at once (the site may have forgotten them while the box was off), the station too.
        suivis.getValue(Canal.FAVORIS).dernierMs = 0L
        suivis.getValue(Canal.STATION).dernierMs = 0L; stationTexte = ""
        suivis.getValue(Canal.DIPLOMES).dernierMs = 0L
        if (p("efface") == "1") runCatching { site.envoieDirect("direct-efface", JSONObject().put("t", horloge())) }
    }

    companion object {
        const val PERIODE_SSTV_MS = 5_000L
        /** The favourites again, unchanged, after an hour. */
        const val PERIODE_FAVORIS_MS = 3_600_000L
        /** A site that does not know `favoris` is asked again the next day. */
        const val NON_PRIS_MS = 24 * 3_600_000L
        /** The awards again, unchanged, after a day. */
        const val PERIODE_DIPLOMES_MS = 24 * 3_600_000L
        /** The station unchanged: sent again after 30 s, so the site knows it is on line. */
        const val BATTEMENT_STATION_MS = 30_000L
        /** A site that does not know `direct-station` is asked again after 10 minutes. */
        const val NON_PRIS_STATION_MS = 10 * 60_000L

        /** The keys [stationJson] may write, each level by itself: what a test checks, what the documentation lists. */
        val CLES_STATION = mapOf(
            "" to setOf("satellite", "antennes", "reception", "enEmission", "mode"),
            "satellite" to setOf("nom", "norad", "geo", "az", "el", "distanceKm", "vitesseKmS", "aos", "los", "elMax", "emetteurs", "choisi"),
            "emetteur" to setOf("description", "mode", "descenteHz", "monteeHz"),
            "antennes" to setOf("rotor", "parabole"),
            "rotor" to setOf("connecte", "az", "el", "cibleAz", "cibleEl", "enMouvement", "horsPortee", "azSeul"),
            "parabole" to setOf("az", "el"),
            "reception" to setOf("sdr", "datv", "poste"),
            "sdr" to setOf("actif", "type", "frequenceHz"),
            "datv" to setOf("actif", "verrouille", "mer", "marge", "service", "cielKhz"),
            "poste" to setOf("modele", "connecte", "descenteHz", "monteeHz", "mode"))

        private fun r1(x: Double) = Math.round(x * 10) / 10.0
        private fun r2(x: Double) = Math.round(x * 100) / 100.0
        private fun mot(x: String, n: Int) = x.replace(Regex("\\p{Cntrl}"), " ").trim().take(n)
        private fun JSONObject.met(k: String, v: Any?): JSONObject { if (v != null && !(v is Double && (v.isNaN() || v.isInfinite())) && v != "") put(k, v); return this }

        /**
         * The station's message (`direct-station`, without its time): only the keys of [CLES_STATION], angles to a
         * tenth of a degree, texts cut short. Never an address, a port, a path or a setting beyond these.
         */
        fun stationJson(s: Station): JSONObject {
            val o = JSONObject()
            s.sat?.let { a ->
                val j = JSONObject().put("nom", mot(a.nom, 40)).put("norad", a.norad).put("geo", a.geo)
                    .met("az", a.az?.let { r1((it % 360.0 + 360.0) % 360.0) }).met("el", a.el?.let { r1(it.coerceIn(-90.0, 90.0)) })
                    .met("distanceKm", a.distanceKm?.let { Math.round(it) }).met("vitesseKmS", a.vitesseKmS?.let { r2(it) })
                if (!a.geo) j.met("aos", a.aos?.takeIf { it > 0 }).met("los", a.los?.takeIf { it > 0 }).met("elMax", a.elMax?.let { r1(it) })
                if (a.emetteurs.isNotEmpty()) j.put("emetteurs", JSONArray(a.emetteurs.take(6).map { e ->
                    JSONObject().put("description", mot(e.description, 60)).met("mode", e.mode?.let { mot(it, 16) })
                        .met("descenteHz", e.descenteHz?.takeIf { it > 0 }).met("monteeHz", e.monteeHz?.takeIf { it > 0 })
                }))
                j.met("choisi", a.choisi?.let { mot(it, 60) })
                o.put("satellite", j)
            }
            val ant = JSONObject()
            s.rotor?.let { r ->
                ant.put("rotor", JSONObject().put("connecte", r.connecte).met("az", r.az?.let { r1((it % 360.0 + 360.0) % 360.0) }).met("el", r.el?.let { r1(it) })
                    .met("cibleAz", r.cibleAz?.let { r1((it % 360.0 + 360.0) % 360.0) }).met("cibleEl", r.cibleEl?.let { r1(it) })
                    .put("enMouvement", r.enMouvement).put("horsPortee", r.horsPortee).put("azSeul", r.azSeul))
            }
            s.parabole?.let { (az, el) -> ant.put("parabole", JSONObject().put("az", r1(az)).put("el", r1(el))) }
            if (ant.length() > 0) o.put("antennes", ant)
            val rec = JSONObject()
            s.sdr?.let { d -> rec.put("sdr", JSONObject().put("actif", d.actif).put("type", mot(d.type, 30)).met("frequenceHz", d.frequenceHz?.takeIf { it > 0 && d.actif })) }
            s.datv?.let { d ->
                val j = JSONObject().put("actif", d.actif).put("verrouille", d.actif && d.verrouille)
                if (d.actif) j.met("cielKhz", d.cielKhz?.takeIf { it > 0 })
                if (d.actif && d.verrouille) j.met("mer", d.mer?.let { r1(it) }).met("marge", d.marge?.let { r1(it) }).met("service", d.service?.let { mot(it, 40) })
                rec.put("datv", j)
            }
            s.poste?.let { p ->
                val j = JSONObject().put("modele", mot(p.modele, 24)).put("connecte", p.connecte)
                if (p.connecte) j.met("descenteHz", p.descenteHz?.takeIf { it > 0 }).met("monteeHz", p.monteeHz?.takeIf { it > 0 }).met("mode", p.mode?.let { mot(it, 12) })
                rec.put("poste", j)
            }
            if (rec.length() > 0) o.put("reception", rec)
            o.put("enEmission", s.enEmission)
            s.mode?.let { o.met("mode", mot(it, 16)) }
            return o
        }

        /** The rig's model as people say it (« IC-9700 »), from SatMe's own codes. */
        fun nomPoste(code: String): String = when (code) {
            "IC9700", "SIM" -> "IC-9700"; "IC9100" -> "IC-9100"; "IC910" -> "IC-910H"; "TS2000", "TS2000SIM" -> "TS-2000"
            "PAIRE" -> "FT-817 (paire)"; "THD72" -> "TH-D72"; else -> code.take(24)
        }

        /** The receiver's public name, from SatMe's codes ([SdrPc.recepteur]). */
        fun nomSdr(code: String): String = when (code) {
            SdrPc.RTL -> "RTL-SDR"; SdrPc.PLUTO -> "ADALM-PLUTO"; SdrPc.BLADERF -> "bladeRF"; SdrPc.NOEUD -> "SatMe-Node"
            SdrPc.FI_POSTE -> "FI du poste"; SdrPc.FICHIER -> "enregistrement I/Q"; SdrPc.GENERATEUR -> "générateur d'essai"; else -> "SDR"
        }

        /** The favourites' message: `{"satellites":[{"nom","norad"}…],"t"}` (sorted by name, then number). */
        fun favorisJson(l: List<Pair<String, Int>>, t: Long): JSONObject = JSONObject()
            .put("satellites", JSONArray(l.filter { it.second > 0 }.distinctBy { it.second }.sortedWith(compareBy({ it.first.lowercase() }, { it.second }))
                .map { JSONObject().put("nom", it.first.take(40)).put("norad", it.second) }))
            .put("t", t)

        private fun raison(t: String): String = runCatching { JSONObject(t).optString("erreur") }.getOrNull()?.takeIf { it.isNotEmpty() } ?: t.take(160)

        /**
         * The auto-pilot's words for the site, from [ap] ([AutoPiloteSstvPc.etatSite]): only the names, times and
         * counts the banner shows, whatever else [ap] carries.
         */
        fun pilote(ap: JSONObject, derniere: Triple<String, Long, String>?, t: Long): JSONObject {
            fun passage(p: JSONObject): JSONObject = JSONObject().put("campagne", p.optString("campagne").take(80)).put("sat", p.optString("sat").take(40))
                .put("aos", p.optLong("aos")).put("los", p.optLong("los")).put("el", p.optLong("el"))
            val o = JSONObject().put("maitre", ap.optBoolean("maitre"))
            ap.optJSONObject("prochain")?.let { o.put("prochain", passage(it)) }
            ap.optJSONObject("enCours")?.let { e ->
                val j = passage(e).put("phase", e.optString("phase")).put("mode", e.optString("mode").take(24)).put("images", e.optInt("images"))
                if (e.has("elMaintenant")) j.put("elMaintenant", e.optLong("elMaintenant")).put("azMaintenant", e.optLong("azMaintenant"))
                e.optJSONObject("image")?.let { i ->
                    j.put("image", JSONObject().put("phase", i.optString("phase")).put("progression", i.optDouble("progression", 0.0).coerceIn(0.0, 1.0))
                        .put("mode", i.optString("mode").take(24)).also { x -> i.optLong("prochaine").takeIf { it > 0 }?.let { x.put("prochaine", it) } })
                }
                o.put("enCours", j)
            }
            if (derniere != null && derniere.second > 0) o.put("derniere", JSONObject().put("sat", derniere.first.take(40)).put("utc", derniere.second).put("mode", derniere.third.take(24)))
            return o
        }

        /** The receiver's figures for the site: lock, the station received (its service), MER, margin, MODCOD, frequency, rate. */
        fun datv(e: EtatDatv, r: ReglageDatv, marge: Double?): JSONObject {
            val o = JSONObject().put("verrouille", e.verrouille).put("cielKhz", r.cielKhz).put("srKs", if (e.verrouille && e.srTrouveKs > 0) e.srTrouveKs else r.srKs)
            if (e.verrouille) {
                o.put("service", e.service.take(40)).put("fournisseur", e.fournisseur.take(40)).put("modcod", e.modcod.take(24))
                e.merDb?.let { o.put("mer", Math.round(it * 10) / 10.0) }
                marge?.let { o.put("marge", it) }
            }
            return o
        }

        /**
         * One spectrum line: [db] reduced to [n] bins (each the strongest of its group, the axis turned over when
         * [largeurHz] is negative), from a little under the noise floor to a little over the strongest signal, one byte
         * per bin, base64. Null when there is nothing to send.
         */
        fun ligneSpectre(db: FloatArray, centreHz: Double, largeurHz: Double, n: Int = 1024): JSONObject? {
            if (db.size < 64 || largeurHz == 0.0 || centreHz <= 0.0) return null
            val m = minOf(n, db.size)
            val r = FloatArray(m)
            for (i in 0 until m) {
                val a = (i.toLong() * db.size / m).toInt(); val b = maxOf(a + 1, ((i + 1).toLong() * db.size / m).toInt())
                var x = Float.NEGATIVE_INFINITY
                for (k in a until b) if (db[k] > x) x = db[k]
                r[i] = x
            }
            if (largeurHz < 0) r.reverse()
            val tries = r.filter { it.isFinite() }.sorted()
            if (tries.isEmpty()) return null
            val bas = tries[tries.size / 10] - 3.0
            val haut = maxOf(tries.last() + 2.0, bas + 30.0)
            val octets = ByteArray(m) { i -> (((if (r[i].isFinite()) r[i].toDouble() else bas) - bas) / (haut - bas) * 255.0).toInt().coerceIn(0, 255).toByte() }
            return JSONObject().put("centreHz", Math.round(centreHz)).put("largeurHz", Math.round(Math.abs(largeurHz)))
                .put("minDb", Math.round(bas * 10) / 10.0).put("maxDb", Math.round(haut * 10) / 10.0).put("bins", Base64.getEncoder().encodeToString(octets))
        }

        /** [b] (a JPEG of the receiver) [largeur] px wide at most, under [max] bytes; null when it cannot be read or made small enough. */
        fun jpegReduit(b: ByteArray, largeur: Int = 640, max: Int = 190 * 1024): ByteArray? {
            val src = runCatching { ImageIO.read(b.inputStream()) }.getOrNull() ?: return null
            val w = minOf(largeur, src.width)
            val h = maxOf(1, Math.round(src.height.toDouble() * w / src.width).toInt())
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(src, 0, 0, w, h, null); g.dispose()
            for (q in listOf(0.78f, 0.6f, 0.45f, 0.3f)) {
                val o = ByteArrayOutputStream()
                val wr = ImageIO.getImageWritersByFormatName("jpg").next()
                try {
                    ImageIO.createImageOutputStream(o).use { s ->
                        wr.output = s
                        val p = wr.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = q }
                        wr.write(null, IIOImage(img, null, null), p)
                    }
                } finally { wr.dispose() }
                if (o.size() <= max) return o.toByteArray()
            }
            return null
        }
    }
}
