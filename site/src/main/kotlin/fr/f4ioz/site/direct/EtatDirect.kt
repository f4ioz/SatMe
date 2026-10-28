/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.direct

import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.Base64

/**
 * What SatMe-PC says live (it pushes, the site never asks the station): the
 * auto-pilot's state, the DATV receiver's figures and its last picture,
 * QO-100's spectrum line by line. In memory only, each part forgetting
 * itself when SatMe-PC stops sending: the auto-pilot after [PILOTE_MS], the
 * DATV figures and picture « hors ligne » after [DATV_MS] (gone after
 * [OUBLI_MS]), the spectrum after [SPECTRE_MS]. What comes is read field by
 * field (known names, bounded strings and numbers); nothing else is kept.
 */
class EtatDirect(private val horloge: () -> Long = System::currentTimeMillis) {

    /** A refused push: its status and why (said to SatMe-PC). */
    class Refus(val statut: Int, message: String) : RuntimeException(message)

    @Volatile private var pilote: JSONObject? = null
    @Volatile private var piloteMs = 0L
    @Volatile private var datv: JSONObject? = null
    @Volatile private var datvMs = 0L
    @Volatile private var image: ByteArray? = null
    @Volatile private var imageMs = 0L
    @Volatile var imageNumero = 0L; private set
    /** The auto-pilot's pass under way, kept after the auto-pilot stops saying it (for the hold after LOS). */
    @Volatile private var passage: JSONObject? = null
    /** The SSTV picture of that pass (arriving, or the last received), and when it came. */
    @Volatile private var imageSstv: ByteArray? = null
    @Volatile private var imageSstvMs = 0L
    @Volatile var imageSstvNumero = 0L; private set

    /** The spectrum's axis (sky Hz), its dB range, the lines kept for the waterfall. */
    private class Axe(val centreHz: Long, val largeurHz: Long, val minDb: Double, val maxDb: Double, val n: Int)
    private class Ligne(val seq: Long, val t: Long, val b64: String)
    @Volatile private var axe: Axe? = null
    private val lignes = ArrayDeque<Ligne>()
    @Volatile var seq = 0L; private set
    @Volatile private var spectreMs = 0L

    // ------------------------------------------------------------------ what SatMe-PC sends

    /** `{"pilote": {…} | null, "datv": {…} | null}`: each part present replaces its own (null clears it). */
    fun recoit(o: JSONObject, pilotePermis: Boolean, datvPermis: Boolean) {
        val t = horloge()
        val ecart = o.optLong("t", 0L).takeIf { it > 0 }?.let { t - it } ?: 0L
        if (o.has("pilote")) {
            if (!pilotePermis && !o.isNull("pilote")) throw Refus(403, "le site n'affiche pas l'auto-pilote (Admin › Paramètres)")
            pilote = o.optJSONObject("pilote")?.let { pilote(it, ecart) }; piloteMs = t
            // A new pass replaces the one kept (and its picture); none said: the last one stays for its hold.
            pilote?.optJSONObject("enCours")?.let { e ->
                if (passage?.optLong("aos") != e.optLong("aos")) { imageSstv = null; imageSstvMs = 0L }
                passage = e
            }
        }
        if (o.has("datv")) {
            if (!datvPermis && !o.isNull("datv")) throw Refus(403, "le site n'affiche pas la DATV (Admin › Paramètres)")
            datv = o.optJSONObject("datv")?.let { datv(it) }; datvMs = t
        }
    }

    /** A DATV picture: a JPEG, [IMAGE_MAX] bytes and 1920 × 1080 at most. */
    fun recoitImage(b: ByteArray) {
        verifieJpeg(b)
        image = b; imageMs = horloge(); imageNumero++
    }

    /** The SSTV picture of the auto-pilot's pass (arriving or last received), same limits; only while a pass is known. */
    fun recoitImageSstv(b: ByteArray) {
        verifieJpeg(b)
        if (passage == null) throw Refus(409, "aucun passage en cours connu du site")
        imageSstv = b; imageSstvMs = horloge(); imageSstvNumero++
    }

    private fun verifieJpeg(b: ByteArray) {
        if (b.size < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte() || b[2] != 0xFF.toByte()) throw Refus(415, "une image JPEG est attendue")
        if (b.size > IMAGE_MAX) throw Refus(413, "image trop grosse (max ${IMAGE_MAX / 1024} Ko)")
        val d = fr.f4ioz.site.publication.Vignettes.dimensions(b) ?: throw Refus(415, "image illisible")
        if (d.first > 1920 || d.second > 1080 || d.first < 16 || d.second < 16) throw Refus(415, "image hors format (1920 × 1080 au plus)")
    }

    /** One spectrum line: `{centreHz, largeurHz, minDb, maxDb, bins}` (bins: one byte per bin, base64, 64 to 2048). */
    fun recoitSpectre(o: JSONObject) {
        val centre = o.optLong("centreHz", 0L)
        val largeur = o.optLong("largeurHz", 0L)
        if (centre !in 1_000_000L..100_000_000_000L || largeur !in 1_000L..100_000_000L) throw Refus(400, "axe du spectre invalide")
        val min = o.optDouble("minDb", Double.NaN); val max = o.optDouble("maxDb", Double.NaN)
        if (min.isNaN() || max.isNaN() || max <= min || min < -250 || max > 250) throw Refus(400, "échelle du spectre invalide")
        val b64 = o.optString("bins")
        if (b64.length > 2800) throw Refus(413, "ligne trop longue (2048 points au plus)")
        val bins = runCatching { Base64.getDecoder().decode(b64) }.getOrNull() ?: throw Refus(400, "points illisibles")
        if (bins.size !in 64..2048) throw Refus(400, "64 à 2048 points par ligne")
        val t = horloge()
        synchronized(lignes) {
            val a = axe
            // Another place or width: the waterfall starts again.
            if (a == null || a.n != bins.size || a.largeurHz != largeur || Math.abs(a.centreHz - centre) > largeur / 100) lignes.clear()
            axe = Axe(centre, largeur, min, max, bins.size)
            seq++
            lignes.addLast(Ligne(seq, t, Base64.getEncoder().encodeToString(bins)))
            while (lignes.size > HISTOIRE) lignes.removeFirst()
        }
        spectreMs = t
    }

    /** Everything forgotten (SatMe-PC's « Direct » switched off). */
    fun efface() {
        pilote = null; datv = null; image = null; imageMs = 0L
        passage = null; imageSstv = null; imageSstvMs = 0L
        synchronized(lignes) { lignes.clear(); axe = null }
        spectreMs = 0L
    }

    // ------------------------------------------------------------------ what visitors see

    fun pilote(): JSONObject? = pilote?.takeIf { horloge() - piloteMs <= PILOTE_MS }

    /** What the live block shows now ([VueDirect.choisis]), with [maintienMs] after LOS or the last picture. */
    fun vue(bascule: Boolean, maintienMs: Long): VueDirect.Vue =
        VueDirect.choisis(passage, imageSstvMs, horloge() - piloteMs <= PILOTE_MS, horloge(), bascule, maintienMs)

    /** The pass the SSTV view shows, with its picture's number and age. */
    fun passageSstv(): JSONObject? {
        val p = passage ?: return null
        val o = JSONObject(p.toString())
        if (imageSstv != null) o.put("image", JSONObject().put("numero", imageSstvNumero).put("age", horloge() - imageSstvMs))
        // The auto-pilot's own countdown of the next picture is under « image » on its side: renamed here.
        p.optJSONObject("image")?.let { o.put("prochaine", it) }
        return o
    }

    fun imageSstv(): ByteArray? = imageSstv?.takeIf { horloge() - imageSstvMs <= OUBLI_MS }
    fun piloteRecuMs(): Long = piloteMs

    /** The DATV figures and its picture's age; « enLigne » false when stale. */
    fun datv(): JSONObject? {
        val t = horloge()
        val d = datv
        val img = image
        if ((d == null || t - datvMs > OUBLI_MS) && (img == null || t - imageMs > OUBLI_MS)) return null
        val o = if (d != null && t - datvMs <= OUBLI_MS) JSONObject(d.toString()) else JSONObject()
        o.put("enLigne", t - maxOf(datvMs, if (img != null) imageMs else 0L) <= DATV_MS && (d?.optBoolean("verrouille") ?: true))
        if (img != null && t - imageMs <= OUBLI_MS) o.put("image", JSONObject().put("numero", imageNumero).put("age", t - imageMs))
        return o
    }

    fun image(): ByteArray? = image?.takeIf { horloge() - imageMs <= OUBLI_MS }

    /** True while lines come. */
    fun spectreActif(): Boolean = axe != null && horloge() - spectreMs <= SPECTRE_MS

    /** The lines after [depuis] (all those kept when 0), [max] at most, the newest last. */
    fun spectre(depuis: Long, max: Int = HISTOIRE): JSONObject? {
        val t = horloge()
        synchronized(lignes) {
            val a = axe ?: return null
            if (t - spectreMs > OUBLI_MS) return null
            val l = lignes.filter { it.seq > depuis }.takeLast(max)
            return JSONObject().put("ok", true).put("seq", seq).put("centreHz", a.centreHz).put("largeurHz", a.largeurHz)
                .put("minDb", a.minDb).put("maxDb", a.maxDb).put("n", a.n).put("age", t - spectreMs).put("actif", t - spectreMs <= SPECTRE_MS)
                .put("lignes", JSONArray(l.map { it.b64 }))
        }
    }

    // ------------------------------------------------------------------ reading what comes

    private fun texte(o: JSONObject, k: String, max: Int = 80): String =
        (o.opt(k) as? String).orEmpty().replace(Regex("\\p{Cntrl}"), " ").trim().take(max)

    private fun nombre(o: JSONObject, k: String, min: Double, max: Double): Double? =
        (o.opt(k) as? Number)?.toDouble()?.takeIf { !it.isNaN() && it in min..max }

    private fun instant(o: JSONObject, k: String, ecart: Long): Long? =
        (o.opt(k) as? Number)?.toLong()?.takeIf { it in 1_500_000_000_000L..4_000_000_000_000L }?.let { if (Math.abs(ecart) > 5_000L) it + ecart else it }

    private fun put(o: JSONObject, k: String, v: Any?) { if (v != null && v != "") o.put(k, v) }

    /** A pass: its campaign, satellite, AOS/LOS and highest elevation. */
    private fun passage(p: JSONObject, ecart: Long): JSONObject? {
        val aos = instant(p, "aos", ecart) ?: return null
        val los = instant(p, "los", ecart) ?: return null
        if (los < aos) return null
        val o = JSONObject().put("aos", aos).put("los", los)
        put(o, "campagne", texte(p, "campagne")); put(o, "sat", texte(p, "sat", 40))
        put(o, "el", nombre(p, "el", 0.0, 90.0)?.let { Math.round(it) })
        return o
    }

    private fun pilote(p: JSONObject, ecart: Long): JSONObject {
        val o = JSONObject().put("maitre", p.optBoolean("maitre"))
        p.optJSONObject("prochain")?.let { passage(it, ecart) }?.let { o.put("prochain", it) }
        p.optJSONObject("enCours")?.let { e ->
            passage(e, ecart)?.let { pe ->
                put(pe, "phase", texte(e, "phase", 20).takeIf { it in setOf("PRET", "EN_COURS") })
                put(pe, "mode", texte(e, "mode", 24))
                put(pe, "images", nombre(e, "images", 0.0, 10_000.0)?.toInt())
                put(pe, "elMaintenant", nombre(e, "elMaintenant", -90.0, 90.0)?.let { Math.round(it) })
                put(pe, "azMaintenant", nombre(e, "azMaintenant", 0.0, 360.0)?.let { Math.round(it) })
                e.optJSONObject("image")?.let { i ->
                    val im = JSONObject()
                    put(im, "phase", texte(i, "phase", 20).takeIf { it in setOf("INCONNU", "EN_COURS", "ATTENTE", "EN_RETARD", "EN_ATTENTE", "FIN_PASSAGE") })
                    put(im, "prochaine", instant(i, "prochaine", ecart))
                    put(im, "progression", nombre(i, "progression", 0.0, 1.0))
                    put(im, "finImage", instant(i, "finImage", ecart))
                    put(im, "mode", texte(i, "mode", 24))
                    if (im.length() > 0) pe.put("image", im)
                }
                o.put("enCours", pe)
            }
        }
        p.optJSONObject("derniere")?.let { d ->
            val utc = instant(d, "utc", 0L) ?: return@let
            val j = JSONObject().put("utc", utc)
            put(j, "sat", texte(d, "sat", 40)); put(j, "mode", texte(d, "mode", 24))
            o.put("derniere", j)
        }
        return o
    }

    private fun datv(d: JSONObject): JSONObject {
        val o = JSONObject().put("verrouille", d.optBoolean("verrouille"))
        put(o, "service", texte(d, "service", 40)); put(o, "fournisseur", texte(d, "fournisseur", 40))
        put(o, "mer", nombre(d, "mer", -10.0, 60.0)); put(o, "marge", nombre(d, "marge", -30.0, 60.0))
        put(o, "modcod", texte(d, "modcod", 24))
        put(o, "cielKhz", nombre(d, "cielKhz", 10_000_000.0, 11_000_000.0)?.toLong())
        put(o, "srKs", nombre(d, "srKs", 25.0, 30_000.0)?.toInt())
        return o
    }

    companion object {
        const val PILOTE_MS = 120_000L
        const val DATV_MS = 30_000L
        const val SPECTRE_MS = 15_000L
        const val OUBLI_MS = 10 * 60_000L
        const val IMAGE_MAX = 200 * 1024
        /** Lines kept for the waterfall (two minutes at one a second). */
        const val HISTOIRE = 120
    }
}
