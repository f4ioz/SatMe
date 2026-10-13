/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.demo.PagePc

import fr.f4ioz.satcombo.demo.PagePlanche
import fr.f4ioz.satcombo.demo.ReponseWeb
import fr.f4ioz.satcombo.sstv.Planche
import fr.f4ioz.satcombo.sstv.SstvMeta
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * The SSTV sheet on SatMe-PC: the phone's page (PagePlanche) and its routes,
 * from the PC's pictures and templates — the same sheet, drawn by
 * [PlancheRenduPc]. Templates are kept in `planches/` beside the log.
 */
class PlancheWebPc(private val station: StationPc, private val son: SonStation, dossier: File) {

    private val rangement = Planche.Rangement(File(dossier, "planches").apply { mkdirs() })

    /** The page, under the PC's header, as the journal's. */
    fun page(): String = PagePlanche.html()
        .replace("<script>", "<script>window.SATME_PC=true;try{localStorage.setItem('satme-cle','pc')}catch(e){}</script>\n<style>${PagePc.STYLE_BARRE}#pupitre,#journal{display:none}</style>\n<script>")
        .replace("<div id=\"editeur\" class=\"cache\">", "<div id=\"editeur\" class=\"cache\">" + PagePc.barre("planche") + "<script>${PagePc.SCRIPT_BARRE}</script>")

    private fun json(s: String) = ReponseWeb("200 OK", "application/json; charset=utf-8", s.toByteArray(Charsets.UTF_8))
    private val NON = ReponseWeb("404 Not Found", "text/plain", "non".toByteArray())
    private val REFUS = ReponseWeb("400 Bad Request", "application/json", "{\"ok\":false}".toByteArray())

    private fun js(t: String): String = buildString {
        for (ch in t) when {
            ch == '\n' -> append("\\n"); ch == '"' -> append("\\\""); ch == '\\' -> append("\\\\"); ch < ' ' -> append(' ')
            else -> append(ch)
        }
    }

    // The pictures are files on disk: read again at most every few seconds.
    @Volatile private var galerie: List<Pair<File, SstvMeta.SstvShot>> = emptyList()
    @Volatile private var galerieMs = 0L
    private fun galerie(): List<Pair<File, SstvMeta.SstvShot>> {
        if (System.currentTimeMillis() - galerieMs > 5_000L) {
            galerie = son.images().mapNotNull { s -> son.fichierImage(s.fileName)?.let { it to s } }
            galerieMs = System.currentTimeMillis()
        }
        return galerie
    }

    // Small pictures, kept: the gallery and the preview ask for them again and again.
    private val vignettes = object : LinkedHashMap<String, BufferedImage>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, BufferedImage>?) = size > 160
    }
    private fun vignette(f: File, w: Int): BufferedImage? = synchronized(vignettes) {
        val cle = f.name + "@" + w
        vignettes[cle] ?: PlancheRenduPc.charge(f, w)?.also { vignettes[cle] = it }
    }

    private fun jpeg(b: BufferedImage, q: Float = 0.88f): ByteArray {
        val o = ByteArrayOutputStream()
        val w = ImageIO.getImageWritersByFormatName("jpg").next()
        ImageIO.createImageOutputStream(o).use { s ->
            w.output = s
            val p = w.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = q }
            w.write(null, IIOImage(b, null, null), p)
        }
        w.dispose()
        return o.toByteArray()
    }

    /** What the texts say: what was typed on the page, else what SatMe would write. */
    private fun valeurs(m: Planche.Modele, places: Map<Int, Pair<File, SstvMeta.SstvShot>>, p: (String) -> String): PlancheRenduPc.Valeurs {
        val s = station.settings
        return PlancheRenduPc.Valeurs(
            indicatif = p("ind").ifBlank { s.callsign },
            nom = p("nom").ifBlank { s.plancheNom },
            locator = p("loc").ifBlank { Planche.locatorDominant(places.values.map { it.second.locator }).ifBlank { station.etat.value.locator } },
            dates = p("dates").ifBlank { Planche.dates(places.values.map { it.second.timeMs }) },
            titre = m.titre.ifBlank { places.values.firstOrNull()?.second?.satName?.let { "$it · SSTV" } ?: "SSTV" })
    }

    private fun places(m: Planche.Modele): Map<Int, Pair<File, SstvMeta.SstvShot>> {
        val parNom = galerie().associateBy { it.first.name }
        return m.images.mapNotNull { (i, f) -> parNom[f]?.let { i to it } }.toMap()
    }

    private fun modele(id: String): Planche.Modele? =
        id.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,40}")) }?.let { i -> rangement.modeles().firstOrNull { it.id == i } }

    /** One request of the sheet page: [route] what follows `/planche`, [corps] the body (a template sent, a picture). */
    fun sert(route: String, methode: String, p: (String) -> String, corps: () -> ByteArray?): ReponseWeb {
        val r = rangement
        val chemin = route.substringBefore('?')
        return runCatching {
            when (chemin) {
                "/liste" -> json("{\"modeles\":" + r.modeles().joinToString(",", "[", "]") { "{\"id\":\"${js(it.id)}\",\"nom\":\"${js(it.nom)}\"}" } +
                    ",\"indicatif\":\"${js(station.settings.callsign)}\",\"nom\":\"${js(station.settings.plancheNom)}\"}")

                "/modele" -> {
                    val m = modele(p("id")) ?: return NON
                    val fond = r.fond(m)
                    val ratio = fond?.let { f -> runCatching { ImageIO.read(f) }.getOrNull()?.let { it.width.toFloat() / it.height } } ?: m.ratio
                    val parNom = galerie().map { it.first.name }.toSet()
                    val (recues, manquent) = Planche.suivi(m) { it in parNom }
                    val v = valeurs(m, places(m)) { "" }
                    json("{\"texte\":\"${js(Planche.ecrit(m))}\",\"ratio\":$ratio,\"fond\":${fond != null},\"recues\":$recues," +
                        "\"manquent\":${manquent.joinToString(",", "[", "]")},\"locator\":\"${js(v.locator)}\",\"dates\":\"${js(v.dates)}\",\"titre\":\"${js(v.titre)}\"}")
                }

                // The template changed on the page, saved as is (its background stays the one it has).
                "/enregistre" -> {
                    if (methode != "POST") return REFUS
                    val avant = modele(p("id")) ?: return NON
                    val lu = corps()?.toString(Charsets.UTF_8)?.let { Planche.lit(avant.id, it) } ?: return REFUS
                    val m = lu.copy(fond = avant.fond, nom = lu.nom.take(60).ifBlank { avant.nom },
                        cases = lu.cases.take(60).map { it.bornee() },
                        textes = lu.textes.take(20).map { it.copy(zone = it.zone.bornee(), libre = it.libre.take(200)) },
                        titre = lu.titre.take(120), images = lu.images.filterKeys { it in lu.cases.indices.take(60) })
                    synchronized(this) { r.enregistre(m) }
                    if (p("nom_station").isNotBlank()) station.settings.plancheNom = p("nom_station").take(60)
                    json("{\"ok\":true}")
                }

                "/nouveau" -> {
                    val nom = fr.f4ioz.satcombo.i18n.tf("planche_nom_modele", r.modeles().size + 1)
                    val m = if (p("type") == "tour") Planche.genereTour(r.nouvelId(), nom) else Planche.genereGrille(r.nouvelId(), nom)
                    synchronized(this) { r.enregistre(m) }
                    json("{\"ok\":true,\"id\":\"${m.id}\"}")
                }

                // A template sent (an ARISS series): kept, its boxes and text frames found.
                "/importe" -> {
                    if (methode != "POST") return REFUS
                    val octets = corps() ?: return REFUS
                    val b = ImageIO.read(octets.inputStream()) ?: return REFUS
                    val grand = if (b.width > 4000) PlancheRenduPc.reduit(b, 4000) else b
                    val id = r.nouvelId()
                    val nomFond = "fond_$id.jpg"
                    File(r.dossier, nomFond).writeBytes(jpeg(rgb(grand), 0.92f))
                    val petit = PlancheRenduPc.reduit(grand, 600)
                    val px = petit.getRGB(0, 0, petit.width, petit.height, null, 0, petit.width)
                    val nom = p("nom").take(60).ifBlank { fr.f4ioz.satcombo.i18n.tf("planche_nom_modele", r.modeles().size + 1) }
                    val m = Planche.genereImporte(id, nom, nomFond, grand.width.toFloat() / grand.height,
                        Planche.detecteCases(px, petit.width, petit.height), Planche.detecteCadresTexte(px, petit.width, petit.height))
                    synchronized(this) { r.enregistre(m) }
                    json("{\"ok\":true,\"id\":\"$id\",\"cases\":${m.cases.size}}")
                }

                // The boxes filled in order of reception, from the pictures shown (one satellite, or all).
                "/remplit" -> {
                    val m = modele(p("id")) ?: return NON
                    val sat = p("sat")
                    val l = galerie().filter { sat.isBlank() || it.second.satName == sat }.map { it.second }
                    synchronized(this) { r.enregistre(m.copy(images = Planche.remplitParReception(m.cases.size, l))) }
                    json("{\"ok\":true}")
                }

                "/galerie" -> json(galerie().joinToString(",", "[", "]") { (f, s) ->
                    "{\"f\":\"${js(f.name)}\",\"s\":\"${js(s.satName)}\",\"m\":\"${js(s.mode)}\",\"t\":${s.timeMs},\"c\":${s.complete},\"d\":${s.source == "live"}}"
                })

                // A picture of the gallery, small (only one of the gallery: never another file).
                "/vignette" -> {
                    val f = galerie().firstOrNull { it.first.name == p("f") }?.first ?: return NON
                    val b = vignette(f, p("w").toIntOrNull()?.coerceIn(80, 800) ?: 320) ?: return NON
                    ReponseWeb("200 OK", "image/jpeg", jpeg(rgb(b), 0.85f), "Cache-Control: max-age=3600\r\n")
                }

                // The sheet: the preview (light), or the sheet itself to download, full size.
                "/apercu", "/exporte" -> {
                    val m = modele(p("id")) ?: return NON
                    val places = places(m)
                    val v = valeurs(m, places, p)
                    if (chemin == "/apercu") {
                        val imgs = places.mapNotNull { (i, x) -> vignette(x.first, 400)?.let { i to (it to x.second) } }.toMap()
                        val fond = r.fond(m)?.let { PlancheRenduPc.charge(it, 1600) }
                        val b = PlancheRenduPc.dessine(m, fond, p("w").toIntOrNull()?.coerceIn(400, 1800) ?: 1400, imgs, v)
                        ReponseWeb("200 OK", "image/jpeg", jpeg(b), "Cache-Control: no-store\r\n")
                    } else {
                        val fond = r.fond(m)?.let { PlancheRenduPc.charge(it, 4000) }
                        val imgs = places.mapNotNull { (i, x) -> PlancheRenduPc.charge(x.first, 2000)?.let { i to (it to x.second) } }.toMap()
                        val b = PlancheRenduPc.dessine(m, fond, fond?.width ?: 2400, imgs, v)
                        val sat = imgs.values.firstOrNull()?.second?.satName?.replace(Regex("[^A-Za-z0-9-]"), "-") ?: "SSTV"
                        val jour = v.dates.take(10).replace("-", "").ifBlank { "planche" }
                        val png = ByteArrayOutputStream().also { ImageIO.write(b, "png", it) }.toByteArray()
                        ReponseWeb("200 OK", "image/png", png, "Content-Disposition: attachment; filename=\"SatMe_Planche_${sat}_$jour.png\"\r\n")
                    }
                }

                else -> NON
            }
        }.getOrElse { json("{\"ok\":false,\"raison\":\"${js(it.javaClass.simpleName)}\"}") }
    }

    /** JPEG wants no transparency: a picture with alpha is laid on black first. */
    private fun rgb(b: BufferedImage): BufferedImage {
        if (b.type == BufferedImage.TYPE_INT_RGB || b.type == BufferedImage.TYPE_3BYTE_BGR) return b
        val r = BufferedImage(b.width, b.height, BufferedImage.TYPE_INT_RGB)
        r.createGraphics().apply { drawImage(b, 0, 0, null); dispose() }
        return r
    }

    companion object {
        /** A template sent from the page: a picture, 25 MB at most. */
        const val IMPORT_MAX = 25L * 1024 * 1024
    }
}
