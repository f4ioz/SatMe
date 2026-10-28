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
 * the day, without an error).
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
        FAVORIS("favoris", "Satellites favoris", 3600, 3600, 3600);

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

    fun on(c: Canal): Boolean = stockage.getBoolean("site_direct_" + c.nom, false)
    fun periode(c: Canal): Int = stockage.getInt("site_direct_" + c.nom + "_s", c.periodeDefaut).coerceIn(c.min, c.max)

    private class Suivi { var prochainMs = 0L; var delaiMs = 0L; var okMs = 0L; var erreur = ""; var envoye = false; var dernier = ""; var dernierMs = 0L
        /** The site answered that it does not know the type (400, 404, 415): not tried again before [prochainMs]. */
        var nonPris = false }
    private val suivis = Canal.values().associateWith { Suivi() }
    private val suiviSstv = Suivi()
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
        for (c in Canal.values()) {
            val s = suivis.getValue(c)
            if (!on(c)) { s.envoye = false; continue }
            if (c == Canal.SPECTRE) runCatching { demandePanorama() }
            if (c == Canal.FAVORIS) { runCatching { favoris(s, t) }.onFailure { e -> echec(s, t, 0, e.message ?: e.javaClass.simpleName) }; continue }
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
            // Its own pace (tic).
            Canal.FAVORIS -> favoris(s, t)
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
        // The favourites sent again at once (the site may have forgotten them while the box was off).
        suivis.getValue(Canal.FAVORIS).dernierMs = 0L
        if (p("efface") == "1") runCatching { site.envoieDirect("direct-efface", JSONObject().put("t", horloge())) }
    }

    companion object {
        const val PERIODE_SSTV_MS = 5_000L
        /** The favourites again, unchanged, after an hour. */
        const val PERIODE_FAVORIS_MS = 3_600_000L
        /** A site that does not know `favoris` is asked again the next day. */
        const val NON_PRIS_MS = 24 * 3_600_000L

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
