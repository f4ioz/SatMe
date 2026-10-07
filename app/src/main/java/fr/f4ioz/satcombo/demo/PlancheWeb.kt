/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import android.content.Context
import android.graphics.Bitmap
import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.sstv.Planche
import fr.f4ioz.satcombo.sstv.PlancheRendu
import fr.f4ioz.satcombo.sstv.SstvHub
import fr.f4ioz.satcombo.sstv.SstvMeta
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The SSTV sheet on the PC: the routes behind `/c/<token>/planche/…`, under
 * the control desk's code.
 *
 * **The phone keeps everything and draws everything.** The PC shows and
 * moves; the preview it shows and the sheet it downloads are drawn by
 * [PlancheRendu], the very drawing of the phone's screen: one sheet, whichever
 * screen it was made on. The template travels in its own text form
 * ([Planche.ecrit]), read back by [Planche.lit].
 *
 * **Nothing is deleted over the network**, as for the log: a template is
 * created, changed, filled — deleted on the phone only.
 */
object PlancheWeb {

    /** The application, set by the ViewModel. */
    @Volatile var app: Context? = null

    class Reponse(val statut: String, val type: String, val corps: ByteArray, val entetes: String = "")

    /** A template sent from the PC: a picture, 25 MB at most. */
    const val IMPORT_MAX = 25L * 1024 * 1024

    private fun json(s: String) = Reponse("200 OK", "application/json; charset=utf-8", s.toByteArray(Charsets.UTF_8))
    private val NON = Reponse("404 Not Found", "text/plain", "non".toByteArray())
    private val REFUS = Reponse("400 Bad Request", "application/json", "{\"ok\":false}".toByteArray())

    private fun js(t: String): String = buildString {
        for (ch in t) when {
            ch == '\n' -> append("\\n")
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch < ' ' -> append(' ')
            else -> append(ch)
        }
    }

    private fun rangement(ctx: Context) = Planche.Rangement(File(ctx.filesDir, "planches"))

    // The gallery is files on disk: read again at most every few seconds.
    @Volatile private var galerie: List<Pair<File, SstvMeta.SstvShot>> = emptyList()
    @Volatile private var galerieMs = 0L
    private fun galerie(ctx: Context): List<Pair<File, SstvMeta.SstvShot>> {
        if (System.currentTimeMillis() - galerieMs > 5_000L) {
            galerie = runCatching { SstvHub.shots(ctx) }.getOrDefault(emptyList()); galerieMs = System.currentTimeMillis()
        }
        return galerie
    }

    // Small pictures, kept: the gallery and the preview ask for them again and again.
    private val vignettes = object : LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Bitmap>?) = size > 160
    }
    private fun vignette(f: File, w: Int): Bitmap? = synchronized(vignettes) {
        val cle = f.name + "@" + w
        vignettes[cle] ?: PlancheRendu.charge(f, w)?.also { vignettes[cle] = it }
    }
    @Volatile private var fondCache: Pair<String, Bitmap>? = null
    private fun fondApercu(r: Planche.Rangement, m: Planche.Modele): Bitmap? {
        val f = r.fond(m) ?: return null
        fondCache?.let { (n, b) -> if (n == f.name) return b }
        return PlancheRendu.charge(f, 1600)?.also { fondCache = f.name to it }
    }

    private fun jpeg(b: Bitmap, q: Int = 88): ByteArray =
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()

    /** What the texts say: what the PC typed, else what the phone would write. */
    private fun valeurs(ctx: Context, m: Planche.Modele, places: Map<Int, Pair<File, SstvMeta.SstvShot>>,
                        p: (String) -> String): PlancheRendu.Valeurs {
        val reglages = SettingsStore(ctx)
        return PlancheRendu.Valeurs(
            indicatif = p("ind").ifBlank { reglages.callsign },
            nom = p("nom").ifBlank { reglages.plancheNom },
            locator = p("loc").ifBlank { Planche.locatorDominant(places.values.map { it.second.locator }).ifBlank { SstvHub.qthLocator } },
            dates = p("dates").ifBlank { Planche.dates(places.values.map { it.second.timeMs }) },
            titre = m.titre.ifBlank { places.values.firstOrNull()?.second?.satName?.let { "$it · SSTV" } ?: "SSTV" })
    }

    private fun places(ctx: Context, m: Planche.Modele): Map<Int, Pair<File, SstvMeta.SstvShot>> {
        val parNom = galerie(ctx).associateBy { it.first.name }
        return m.images.mapNotNull { (i, f) -> parNom[f]?.let { i to it } }.toMap()
    }

    private fun modele(r: Planche.Rangement, id: String): Planche.Modele? =
        id.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,40}")) }?.let { i -> r.modeles().firstOrNull { it.id == i } }

    /**
     * One request of the sheet page. [route] is what follows `/planche`;
     * [p] reads a parameter; [corps] reads the body (a template sent).
     */
    fun sert(route: String, methode: String, p: (String) -> String, corps: () -> ByteArray?): Reponse {
        val ctx = app ?: return json("{\"ok\":false,\"raison\":\"pasPret\"}")
        val r = rangement(ctx)
        val chemin = route.substringBefore('?')
        return runCatching {
            when (chemin) {
                // The templates, and what the texts say by default.
                "/liste" -> {
                    val reglages = SettingsStore(ctx)
                    json("{\"modeles\":" + r.modeles().joinToString(",", "[", "]") { "{\"id\":\"${js(it.id)}\",\"nom\":\"${js(it.nom)}\"}" } +
                        ",\"indicatif\":\"${js(reglages.callsign)}\",\"nom\":\"${js(reglages.plancheNom)}\"}")
                }

                // A template in its text form, with the size of its background and where the series stands.
                "/modele" -> {
                    val m = modele(r, p("id")) ?: return NON
                    val fond = r.fond(m)
                    val ratio = fond?.let { f ->
                        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)
                        if (o.outHeight > 0) o.outWidth.toFloat() / o.outHeight else null
                    } ?: m.ratio
                    val parNom = galerie(ctx).map { it.first.name }.toSet()
                    val (recues, manquent) = Planche.suivi(m) { it in parNom }
                    val v = valeurs(ctx, m, places(ctx, m)) { "" }
                    json("{\"texte\":\"${js(Planche.ecrit(m))}\",\"ratio\":$ratio," +
                        "\"fond\":${fond != null},\"recues\":$recues,\"manquent\":${manquent.joinToString(",", "[", "]")}," +
                        "\"locator\":\"${js(v.locator)}\",\"dates\":\"${js(v.dates)}\",\"titre\":\"${js(v.titre)}\"}")
                }

                // The template changed on the PC, saved as is (its background stays the one it has).
                "/enregistre" -> {
                    if (methode != "POST") return REFUS
                    val avant = modele(r, p("id")) ?: return NON
                    val lu = corps()?.toString(Charsets.UTF_8)?.let { Planche.lit(avant.id, it) } ?: return REFUS
                    val m = lu.copy(fond = avant.fond, nom = lu.nom.take(60).ifBlank { avant.nom },
                        cases = lu.cases.take(60).map { it.bornee() },
                        textes = lu.textes.take(20).map { it.copy(zone = it.zone.bornee(), libre = it.libre.take(200)) },
                        titre = lu.titre.take(120),
                        images = lu.images.filterKeys { it in lu.cases.indices.take(60) })
                    synchronized(this) { r.enregistre(m) }
                    if (p("nom_station").isNotBlank()) SettingsStore(ctx).plancheNom = p("nom_station").take(60)
                    json("{\"ok\":true}")
                }

                // A new template, generic.
                "/nouveau" -> {
                    val n = r.modeles().size + 1
                    val nom = fr.f4ioz.satcombo.i18n.tf("planche_nom_modele", n)
                    val m = if (p("type") == "tour") Planche.genereTour(r.nouvelId(), nom) else Planche.genereGrille(r.nouvelId(), nom)
                    synchronized(this) { r.enregistre(m) }
                    json("{\"ok\":true,\"id\":\"${m.id}\"}")
                }

                // A template sent from the PC (an ARISS series): kept, its boxes and frames found.
                "/importe" -> {
                    if (methode != "POST") return REFUS
                    val octets = corps() ?: return REFUS
                    val b = android.graphics.BitmapFactory.decodeByteArray(octets, 0, octets.size) ?: return REFUS
                    val grand = if (b.width > 4000) Bitmap.createScaledBitmap(b, 4000, (4000f * b.height / b.width).toInt(), true) else b
                    val id = r.nouvelId()
                    val nomFond = "fond_$id.jpg"
                    File(r.dossier, nomFond).outputStream().use { grand.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    val w = 600; val h = (600f * grand.height / grand.width).toInt().coerceAtLeast(1)
                    val petit = Bitmap.createScaledBitmap(grand, w, h, true)
                    val px = IntArray(w * h); petit.getPixels(px, 0, w, 0, 0, w, h)
                    val nom = p("nom").take(60).ifBlank { fr.f4ioz.satcombo.i18n.tf("planche_nom_modele", r.modeles().size + 1) }
                    val m = Planche.genereImporte(id, nom, nomFond, grand.width.toFloat() / grand.height,
                        Planche.detecteCases(px, w, h), Planche.detecteCadresTexte(px, w, h))
                    synchronized(this) { r.enregistre(m) }
                    json("{\"ok\":true,\"id\":\"$id\",\"cases\":${m.cases.size}}")
                }

                // The boxes filled in order of reception, from the pictures shown (one satellite, or all).
                "/remplit" -> {
                    val m = modele(r, p("id")) ?: return NON
                    val sat = p("sat")
                    val l = galerie(ctx).filter { sat.isBlank() || it.second.satName == sat }.map { it.second }
                    val n = m.copy(images = Planche.remplitParReception(m.cases.size, l))
                    synchronized(this) { r.enregistre(n) }
                    json("{\"ok\":true}")
                }

                // The SSTV pictures received, newest first.
                "/galerie" -> json(galerie(ctx).joinToString(",", "[", "]") { (f, s) ->
                    "{\"f\":\"${js(f.name)}\",\"s\":\"${js(s.satName)}\",\"m\":\"${js(s.mode)}\",\"t\":${s.timeMs}," +
                        "\"c\":${s.complete},\"d\":${s.source == "live"}}"
                })

                // A picture of the gallery, small (only one of the gallery: never another file).
                "/vignette" -> {
                    val f = galerie(ctx).firstOrNull { it.first.name == p("f") }?.first ?: return NON
                    val b = vignette(f, p("w").toIntOrNull()?.coerceIn(80, 800) ?: 320) ?: return NON
                    Reponse("200 OK", "image/jpeg", jpeg(b, 85), "Cache-Control: max-age=3600\r\n")
                }

                // The sheet as the phone draws it: the preview (light), or the sheet itself to download.
                "/apercu", "/exporte" -> {
                    val m = modele(r, p("id")) ?: return NON
                    val places = places(ctx, m)
                    val v = valeurs(ctx, m, places, p)
                    val logo = runCatching { ctx.packageManager.getApplicationIcon(ctx.applicationInfo) }.getOrNull()
                    if (chemin == "/apercu") {
                        val imgs = places.mapNotNull { (i, x) -> vignette(x.first, 400)?.let { i to (it to x.second) } }.toMap()
                        val b = PlancheRendu.dessine(m, fondApercu(r, m), p("w").toIntOrNull()?.coerceIn(400, 1800) ?: 1400, imgs, v, logo)
                        Reponse("200 OK", "image/jpeg", jpeg(b), "Cache-Control: no-store\r\n")
                    } else {
                        val fond = r.fond(m)?.let { PlancheRendu.charge(it, 4000) }
                        val imgs = places.mapNotNull { (i, x) -> PlancheRendu.charge(x.first, 2000)?.let { i to (it to x.second) } }.toMap()
                        val b = PlancheRendu.dessine(m, fond, fond?.width ?: 2400, imgs, v, logo)
                        val sat = imgs.values.firstOrNull()?.second?.satName?.replace(Regex("[^A-Za-z0-9-]"), "-") ?: "SSTV"
                        val jour = v.dates.take(10).replace("-", "").ifBlank { "planche" }
                        val png = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                        Reponse("200 OK", "image/png", png,
                            "Content-Disposition: attachment; filename=\"SatMe_Planche_${sat}_$jour.png\"\r\n")
                    }
                }

                else -> NON
            }
        }.getOrElse { json("{\"ok\":false,\"raison\":\"${js(it.javaClass.simpleName)}\"}") }
    }
}
