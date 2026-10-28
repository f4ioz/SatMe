/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.Diplomes
import org.json.JSONArray
import org.json.JSONObject
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * « Agenda › Diplômes » on a web site, through « Publication web » (both off
 * by default):
 *
 * - **`diplomes`** (the line « Diplômes » of the card, [DirectSitePc.Canal.DIPLOMES]):
 *   per award its name, its issuer, standing or event, the owner's own
 *   progress (current / target / unit), where it stands (in progress,
 *   reached, asked for, received; an event to come or under way), its dates,
 *   the link to its rules — [json], from a short list of keys ([CLES]). Never
 *   what was counted (the squares, the entities, the stations worked), the
 *   pictures' names, the notes, the name or the e-mail of « Pour les
 *   demandes », nor a hidden, missed or expired award.
 * - **`diplome-image`**: the picture of an award received, only when the owner
 *   clicks « Envoyer le diplôme reçu » on it ([envoie]); a PNG or a JPEG as it
 *   was kept (a PDF as it is), made smaller when it is over [MAX_OCTETS].
 *
 * Which awards' pictures went is kept in `diplomes-site.json` (id → when).
 */
class DiplomesSitePc(
    private val fichier: File,
    private val site: SitePc,
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var envoyes: Map<String, Long> = runCatching {
        val o = JSONObject(fichier.readText())
        o.keySet().associateWith { o.optLong(it) }
    }.getOrDefault(emptyMap())

    /** The awards whose picture the site took, and when. */
    fun envoyes(): Map<String, Long> = envoyes

    /** Whether a click can send now: the module on and its target tested. */
    val pret: Boolean get() = site.configure

    /** « Envoyer le diplôme reçu » on award [id], its kept picture [f]: null when the site took it, else why not. */
    fun envoie(id: String, f: File?): String? {
        if (!site.configure) return "Publication web non prête (Réglages › Services en ligne › Envoyer vers un site web)"
        if (f == null || !f.isFile) return "aucune image de ce diplôme"
        val doc = prepare(f.readBytes()) ?: return "image illisible ou trop grande (PNG, JPEG ou PDF)"
        val nom = "diplome." + doc.ext
        val r = runCatching { site.envoieDocument("diplome-image", JSONObject().put("diplome", id), nom, doc.mime, doc.ext, doc.octets) }
            .getOrElse { return "pas de réponse : " + (it.message ?: it.javaClass.simpleName) } ?: return "site non réglé"
        return when {
            r.first in 200..299 -> { garde(envoyes + (id to horloge())); null }
            r.first == 400 || r.first == 404 || r.first == 415 -> "non pris en charge par le site (HTTP ${r.first})"
            r.first == 401 -> "jeton refusé par le site"
            else -> "HTTP " + r.first + " : " + (runCatching { JSONObject(r.second).optString("erreur") }.getOrNull()?.takeIf { it.isNotEmpty() } ?: r.second.take(160))
        }
    }

    @Synchronized private fun garde(m: Map<String, Long>) {
        envoyes = m
        runCatching {
            fichier.parentFile?.mkdirs()
            val tmp = File(fichier.parentFile, fichier.name + ".tmp")
            tmp.writeText(JSONObject(m).toString())
            if (!tmp.renameTo(fichier)) { fichier.delete(); tmp.renameTo(fichier) }
        }
    }

    /** A file ready to go: its bytes, type and extension. */
    class Document(val octets: ByteArray, val mime: String, val ext: String)

    companion object {
        /** The site's limit for `diplome-image` (8 MB); a larger picture is made smaller. */
        const val MAX_OCTETS = 8 * 1024 * 1024
        /** A picture made smaller: at most this many pixels on its longer side. */
        const val COTE_MAX = 3000

        /** The keys an award may carry to the site, and those of its `progression`: nothing else ever goes. */
        val CLES = setOf("id", "nom", "emetteur", "sorte", "statut", "libelle", "progression", "debut", "fin", "limite", "demande", "recu",
            "reglement", "satellite", "mode", "image")
        val CLES_PROGRESSION = setOf("actuel", "objectif", "unite")

        /** Where an award stands, as the site's API says it; null: not sent (hidden, missed, expired, rules to check). */
        fun statut(s: Diplomes.Statut): String? = when (s) {
            Diplomes.Statut.EN_COURS -> "en_cours"
            Diplomes.Statut.OBTENABLE -> "obtenable"
            Diplomes.Statut.DEMANDE -> "demande"
            Diplomes.Statut.RECU -> "recu"
            Diplomes.Statut.A_VENIR -> "a_venir"
            Diplomes.Statut.PENDANT -> "pendant"
            Diplomes.Statut.EXPIRE, Diplomes.Statut.MANQUE, Diplomes.Statut.A_VERIFIER, Diplomes.Statut.MASQUE -> null
        }

        private fun texte(s: String, n: Int) = s.replace(Regex("\\p{Cntrl}"), " ").replace(Regex("\\s+"), " ").trim().take(n)
        private fun https(s: String) = s.trim().takeIf { it.startsWith("https://") && it.length <= 400 && it.none { c -> c.isWhitespace() || c == '"' || c == '<' || c == '>' } }

        /**
         * The awards' message (`diplomes`, without its time): `{"diplomes":[…]}`, each award with the keys of [CLES]
         * only. [images]: the awards whose picture the site has ([envoyes]).
         */
        fun json(bilans: List<Diplomes.Bilan>, images: Set<String> = emptySet()): JSONObject = JSONObject().put("diplomes", JSONArray(bilans.mapNotNull { b ->
            val d = b.diplome
            val st = statut(b.statut) ?: return@mapNotNull null
            val o = JSONObject().put("id", d.id.take(200)).put("nom", texte(d.nom, 160)).put("emetteur", texte(d.emetteur, 120))
                .put("sorte", if (d.evenement) "evenement" else "permanent").put("statut", st)
            if (b.libelle.isNotBlank()) o.put("libelle", texte(b.libelle, 160))
            if (!d.evenement && d.genre != Diplomes.Genre.LIBRE && d.objectif > 0)
                o.put("progression", JSONObject().put("actuel", b.compte).put("objectif", d.objectif).put("unite", texte(d.genre.unite, 40)))
            if (d.debutMs > 0) o.put("debut", d.debutMs)
            if (d.finMs > 0) o.put("fin", d.finMs)
            if (d.limiteMs > 0) o.put("limite", d.limiteMs)
            if (b.suivi.etat == Diplomes.EtatSuivi.DEMANDE && b.suivi.quandMs > 0) o.put("demande", b.suivi.quandMs)
            if (b.suivi.etat == Diplomes.EtatSuivi.RECU && b.suivi.quandMs > 0) o.put("recu", b.suivi.quandMs)
            (https(d.lienReglement) ?: https(d.sourceLien))?.let { o.put("reglement", it) }
            if (d.evenement && d.satNom.isNotBlank()) o.put("satellite", texte(d.satNom, 60))
            if (d.evenement && d.mode.isNotBlank()) o.put("mode", texte(d.mode, 30))
            if (d.id in images) o.put("image", true)
            o
        }))

        /**
         * [b] ready for the site: a PDF as it is (8 MB at most), a PNG or a JPEG as it is when small enough, otherwise
         * drawn again as a JPEG at most [COTE_MAX] px on its longer side; null when it is none of them.
         */
        fun prepare(b: ByteArray): Document? {
            val pdf = b.size > 5 && String(b, 0, 5, Charsets.ISO_8859_1) == "%PDF-"
            if (pdf) return if (b.size <= MAX_OCTETS) Document(b, "application/pdf", "pdf") else null
            val png = b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()
            val jpeg = b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()
            if ((png || jpeg) && b.size <= MAX_OCTETS) return Document(b, if (png) "image/png" else "image/jpeg", if (png) "png" else "jpg")
            val img = runCatching { ImageIO.read(ByteArrayInputStream(b)) }.getOrNull() ?: return null
            var q = 0.88f
            var cote = COTE_MAX
            while (cote >= 600) {
                val j = jpeg(reduit(img, cote), q)
                if (j.size <= MAX_OCTETS) return Document(j, "image/jpeg", "jpg")
                cote = (cote * 0.75).toInt(); q = maxOf(0.7f, q - 0.05f)
            }
            return null
        }

        private fun reduit(src: BufferedImage, cote: Int): BufferedImage {
            val k = minOf(1.0, cote.toDouble() / maxOf(src.width, src.height))
            val w = maxOf(1, (src.width * k).toInt()); val h = maxOf(1, (src.height * k).toInt())
            val dst = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = dst.createGraphics()
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.color = java.awt.Color.WHITE; g.fillRect(0, 0, w, h)
            g.drawImage(src, 0, 0, w, h, null); g.dispose()
            return dst
        }

        private fun jpeg(img: BufferedImage, qualite: Float): ByteArray {
            val o = ByteArrayOutputStream()
            val w = ImageIO.getImageWritersByFormatName("jpeg").next()
            ImageIO.createImageOutputStream(o).use { s ->
                w.output = s
                val p = w.defaultWriteParam
                p.compressionMode = ImageWriteParam.MODE_EXPLICIT; p.compressionQuality = qualite
                w.write(null, IIOImage(img, null, null), p)
            }
            w.dispose()
            return o.toByteArray()
        }
    }
}
