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

import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.demo.ApiPlancheEvenement
import fr.f4ioz.satcombo.demo.PagePlanche
import fr.f4ioz.satcombo.domain.CampagneSstv
import fr.f4ioz.satcombo.sstv.ClassementGalerie
import fr.f4ioz.satcombo.sstv.EvenementSstv
import fr.f4ioz.satcombo.sstv.RapportEvenement
import fr.f4ioz.satcombo.sstv.SerieSstv
import fr.f4ioz.satcombo.demo.ReponseWeb
import fr.f4ioz.satcombo.sstv.Planche
import fr.f4ioz.satcombo.sstv.PlancheDecor
import fr.f4ioz.satcombo.sstv.PlancheFichier
import fr.f4ioz.satcombo.sstv.SstvMeta
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * The SSTV sheet on SatMe-PC: the phone's page (PagePlanche) and its routes,
 * from the PC's pictures and templates — the same sheet, drawn by
 * [PlancheRenduPc]. Templates are kept in `planches/` beside the log.
 */
class PlancheWebPc(private val station: StationPc, private val son: SonStation, dossier: File) {

    /** The gallery sorted: « Remplir » takes the best copy of each slide (set by Main). */
    @Volatile var classement: ClassementSstvPc? = null

    /** The station's plans, for the report's receive chain (set by Main). */
    @Volatile var montage: MontagePc? = null

    private val rangement = Planche.Rangement(File(dossier, "planches").apply { mkdirs() })

    /** What the sheet's event, its picker and its report read on the PC. */
    private val source = object : ApiPlancheEvenement.Source {
        override val dossier: File get() = son.sstv.dossier
        override val classement: ClassementGalerie get() = this@PlancheWebPc.classement?.galerie ?: secours
        private val secours = ClassementGalerie { null }
        override fun images(): List<SstvMeta.SstvShot> = galerie().map { it.second }
        override fun evenements(): List<EvenementSstv.Evenement> = this@PlancheWebPc.classement?.evenements().orEmpty()
        override fun catnumDe(sat: String): Int = station.catnumDe(sat)
        override fun passes(catnum: Int, deMs: Long, aMs: Long): List<SatPass> = station.passagesDe(catnum, deMs, aMs)
        override fun positions(catnum: Int, ts: List<Long>): List<Pair<Double, Double>>? = station.positionsDe(catnum, ts)
        override fun epoqueElements(catnum: Int): Long? = station.epoqueElements(catnum)
        override fun traces(): List<CampagneSstv.Trace> = this@PlancheWebPc.classement?.traces().orEmpty()
        override fun campagne(id: String): CampagneSstv.Campagne? = this@PlancheWebPc.classement?.campagne(id)
        override fun elMin(): Double = SerieSstv.elMin(station.settings.minElevDeg.toDouble())
        override fun station(): RapportEvenement.Station =
            RapportEvenement.Station(station.settings.callsign, station.etat.value.locator, station.settings.plancheNom)
        override fun plans(): List<Pair<String, String>> = montage?.listePlans().orEmpty()
        override fun chaine(plan: String): Pair<String, List<List<RapportEvenement.Etape>>>? =
            montage?.chaineDe(plan)?.let { (nom, c) -> nom to RapportEvenement.etapes(c) }
        override fun planche(m: Planche.Modele): ByteArray? = image(m.id, 1600)?.let { jpeg(rgb(it), 0.86f) }
        override fun vignette(nom: String): ByteArray? =
            galerie().firstOrNull { it.first.name == nom }?.first?.let { vignette(it, 360) }?.let { jpeg(rgb(it), 0.84f) }
        override fun pdf(r: RapportEvenement.Rapport): ByteArray = RapportPdfPc.pdf(r)
    }

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
            galerie = son.images().filter { !SstvPc.horsAntenne(it) }.mapNotNull { s -> son.fichierImage(s.fileName)?.let { it to s } }
            galerieMs = System.currentTimeMillis()
        }
        return galerie
    }

    // Small pictures, kept: the gallery and the preview ask for them again and again.
    private val vignettes = object : LinkedHashMap<String, BufferedImage>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, BufferedImage>?) = size > 160
    }
    private fun vignette(f: File, w: Int): BufferedImage? = synchronized(vignettes) {
        val cle = f.name + "@" + w + "@" + f.lastModified()
        vignettes[cle] ?: PlancheRenduPc.charge(f, w)?.also { vignettes[cle] = it }
    }

    private fun jpeg(b: BufferedImage, q: Float = 0.88f): ByteArray = PlancheRenduPc.jpeg(b, q)

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
        // The processed versions, when the sheet takes them: marked so.
        val corr = if (m.corrigees) classement?.corrections?.api else null
        return m.images.mapNotNull { (i, f) -> parNom[f]?.let { i to (corr?.pourPlanche(it) ?: it) } }.toMap()
    }

    private fun modele(id: String): Planche.Modele? =
        id.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,40}")) }?.let { i -> rangement.modeles().firstOrNull { it.id == i } }

    /** The templates: id and name (the SSTV transmission's choice). */
    fun modeles(): List<Pair<String, String>> = rangement.modeles().map { it.id to it.nom }

    /** The sheet [id] as the preview draws it, [largeur] wide (sent in SSTV), or null. */
    fun image(id: String, largeur: Int = 1200): BufferedImage? {
        val m = modele(id) ?: return null
        val places = places(m)
        val imgs = places.mapNotNull { (i, x) -> vignette(x.first, 400)?.let { i to (it to x.second) } }.toMap()
        val fond = rangement.fond(m)?.let { PlancheRenduPc.charge(it, 1600) }
        return PlancheRenduPc.dessine(m, fond, largeur, imgs, valeurs(m, places) { "" },
            decor = PlancheRenduPc.chargeDecor(rangement, m, 1600), ecussons = PlancheRenduPc.chargeEcussons(rangement, m, 800))
    }

    /** A picture sent from the page, read; null when it is none. */
    private fun lisImage(octets: ByteArray?): BufferedImage? = octets?.let { runCatching { ImageIO.read(it.inputStream()) }.getOrNull() }

    /** One request of the sheet page: [route] what follows `/planche`, [corps] the body (a template sent, a picture). */
    fun sert(route: String, methode: String, p: (String) -> String, corps: () -> ByteArray?): ReponseWeb {
        val r = rangement
        val chemin = route.substringBefore('?')
        return runCatching {
            ApiPlancheEvenement.sert(chemin, p, source, r, this, ::modele)?.let { return it }
            when (chemin) {
                "/liste" -> json("{\"modeles\":" + r.modeles().joinToString(",", "[", "]") { "{\"id\":\"${js(it.id)}\",\"nom\":\"${js(it.nom)}\",\"ev\":\"${js(it.evenement?.nom.orEmpty())}\",\"n\":${it.images.size}}" } +
                    ",\"indicatif\":\"${js(station.settings.callsign)}\",\"nom\":\"${js(station.settings.plancheNom)}\"}")

                "/modele" -> {
                    val m = modele(p("id"))?.let { ApiPlancheEvenement.assure(it, source, r, this) } ?: return NON
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
                        titre = lu.titre.take(120), images = lu.images.filterKeys { it in lu.cases.indices.take(60) },
                        origine = lu.origine.take(60).map { it.bornee() }, textesOrigine = lu.textesOrigine.take(20))
                        .let { Planche.decorVerifie(it) { n -> r.fichier(n) != null } }
                    synchronized(this) { r.enregistre(m); r.nettoie() }
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
                    if (PlancheFichier.estZip(octets)) return importeModele(octets)
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

                // The template as one file, to give to another station: no picture received, the callsign a field unless [indicatif].
                "/modele-exporte" -> {
                    val m = modele(p("id")) ?: return NON
                    val f = PlancheFichier.exporte(m, r, { PlancheRenduPc.ecussonDetoure(it) },
                        station.settings.callsign.takeIf { p("indicatif") == "1" })
                    ReponseWeb("200 OK", "application/zip", f,
                        "Content-Disposition: attachment; filename=\"${PlancheFichier.nomFichier(m)}\"\r\nCache-Control: no-store\r\n")
                }

                // A template file (.satme-planche) sent: a new template, never over another one.
                "/modele-importe" -> {
                    if (methode != "POST") return REFUS
                    importeModele(corps() ?: return REFUS)
                }

                // The boxes filled in order of reception, from the pictures shown (one satellite, or all).
                // [n]: the sheet fitted to that many pictures (0: the template's boxes); one copy of each slide.
                "/remplit", "/nombre" -> {
                    val m = modele(p("id")) ?: return NON
                    // The pictures shown: the sheet's event's (unless « all »), or one satellite's.
                    val noms = ApiPlancheEvenement.images(m, source, p("sat"), p("tous") == "1").map { it.fileName }.toHashSet()
                    val l = galerie().filter { it.second.fileName in noms }
                    val sig = PlancheRenduPc.signatures(l.map { it.first }).let { b ->
                        classement?.let { c -> runCatching { c.galerie.signatures(b, c.etat(attendre = true)) }.getOrNull() } ?: b }
                    val n = if (chemin == "/remplit") Planche.remplit(m, l.map { it.second }, sig)
                        else Planche.avecNombre(m, p("n").toIntOrNull()?.coerceIn(0, Planche.nombreMax(m)) ?: 0, l.map { it.second }, sig)
                    synchronized(this) { r.enregistre(n) }
                    json("{\"ok\":true,\"cases\":${n.cases.size}}")
                }

                // A picture put on a fitted sheet, or taken off it.
                "/bascule" -> {
                    val m = modele(p("id")) ?: return NON
                    val f = galerie().firstOrNull { it.first.name == p("f") }?.first?.name ?: return NON
                    val n = Planche.bascule(m, f)
                    synchronized(this) { r.enregistre(n) }
                    json("{\"ok\":${n != m || f !in Planche.choisies(m)},\"cases\":${n.cases.size}}")
                }

                // The built-in backgrounds, to choose one.
                "/decors" -> json(PlancheDecor.INTEGRES.joinToString(",", "[", "]") {
                    "{\"id\":\"${js(it.id)}\",\"nom\":\"${js(fr.f4ioz.satcombo.i18n.t(it.cle))}\",\"nasa\":${it.ressource != null}}"
                })

                // A background small: a built-in one, or the picture chosen for a sheet.
                "/decor-vignette" -> {
                    val d = p("d")
                    val w = p("w").toIntOrNull()?.coerceIn(80, 600) ?: 240
                    val b = PlancheDecor.integre(d)?.let { PlancheRenduPc.apercuDecor(it, w) }
                        ?: r.fichier(d)?.takeIf { d.startsWith("decor_") }?.let { f -> PlancheRenduPc.charge(f, w) } ?: return NON
                    ReponseWeb("200 OK", "image/jpeg", jpeg(rgb(b), 0.85f), "Cache-Control: max-age=3600\r\n")
                }

                // A picture of one's own as the background: kept (3000 pixels at most), fitted to the sheet when drawn.
                "/decor" -> {
                    if (methode != "POST") return REFUS
                    val m = modele(p("id")) ?: return NON
                    val b = lisImage(corps()) ?: return REFUS
                    val nom = r.nouveauNom("decor", "jpg")
                    File(r.dossier, nom).writeBytes(jpeg(rgb(if (b.width > 3000) PlancheRenduPc.reduit(b, 3000) else b), 0.9f))
                    synchronized(this) { r.enregistre(m.copy(decor = nom)); r.nettoie() }
                    json("{\"ok\":true,\"decor\":\"$nom\"}")
                }

                // A patch: kept as PNG (its transparency, if it has one), put near the middle of the sheet.
                "/ecusson" -> {
                    if (methode != "POST") return REFUS
                    val m = modele(p("id")) ?: return NON
                    if (m.ecussons.size >= Planche.ECUSSONS_MAX) return json("{\"ok\":false,\"raison\":\"plein\"}")
                    val b = lisImage(corps()) ?: return REFUS
                    val petit = if (maxOf(b.width, b.height) > 1600) {
                        val l = if (b.width >= b.height) 1600 else (1600L * b.width / b.height).toInt().coerceAtLeast(1)
                        BufferedImage(l, (l.toLong() * b.height / b.width).toInt().coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB).also {
                            it.createGraphics().apply {
                                setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                                drawImage(b, 0, 0, it.width, it.height, null); dispose()
                            }
                        }
                    } else b
                    val nom = r.nouveauNom("ecusson", "png")
                    File(r.dossier, nom).outputStream().use { ImageIO.write(petit, "png", it) }
                    val n = Planche.ajouteEcusson(m, nom, petit.width, petit.height)
                    synchronized(this) { r.enregistre(n) }
                    json("{\"ok\":true,\"n\":${n.ecussons.size}}")
                }

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
                        val b = PlancheRenduPc.dessine(m, fond, p("w").toIntOrNull()?.coerceIn(400, 1800) ?: 1400, imgs, v,
                            decor = PlancheRenduPc.chargeDecor(r, m, 1800), ecussons = PlancheRenduPc.chargeEcussons(r, m, 800))
                        ReponseWeb("200 OK", "image/jpeg", jpeg(b), "Cache-Control: no-store\r\n")
                    } else {
                        val fond = r.fond(m)?.let { PlancheRenduPc.charge(it, 4000) }
                        val imgs = places.mapNotNull { (i, x) -> PlancheRenduPc.charge(x.first, 2000)?.let { i to (it to x.second) } }.toMap()
                        val b = PlancheRenduPc.dessine(m, fond, fond?.width ?: 2400, imgs, v,
                            decor = PlancheRenduPc.chargeDecor(r, m, 4000), ecussons = PlancheRenduPc.chargeEcussons(r, m, 1600))
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

    /** A template file made a new template: its id, name and boxes; or why it was refused. */
    private fun importeModele(octets: ByteArray): ReponseWeb = try {
        val m = synchronized(this) { PlancheFichier.importe(octets, rangement) }
        json("{\"ok\":true,\"id\":\"${m.id}\",\"nom\":\"${js(m.nom)}\",\"cases\":${m.cases.size}}")
    } catch (e: PlancheFichier.Refus) { json("{\"ok\":false,\"raison\":\"${e.raison}\"}") }

    private fun rgb(b: BufferedImage): BufferedImage = PlancheRenduPc.rgb(b)

    companion object {
        /** A template sent from the page: a picture, 25 MB at most. */
        const val IMPORT_MAX = 25L * 1024 * 1024
    }
}
