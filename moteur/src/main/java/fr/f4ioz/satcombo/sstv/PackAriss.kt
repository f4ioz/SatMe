/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.station.Montage
import fr.f4ioz.satcombo.station.PlansMontage
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The ARISS SSTV award pack: what the owner attaches to his request, ready
 * — the chosen pictures as received (the original files, byte for byte),
 * their QSL version, and a summary (text and HTML) with what the gallery's
 * form asks: callsign, grid square, date and UTC time of the capture, the
 * campaign; and the reception: satellite, frequency, mode, the pass, the
 * station's equipment (from the « Montage » plan when there is one).
 *
 * Nothing is sent anywhere: the owner uploads the original himself on the
 * ARISS gallery (https://ariss-usa.org/ARISS_SSTV/submit.php), which asks
 * for « clean » pictures — no callsign added, no composite: the QSL is for
 * him, not for the form. The rules as read in October 2026 (see
 * docs/station-pc.md): one recognisable picture of the session, partial
 * accepted (https://ariss.pzk.org.pl/sstv/); png, jpg or gif, 800 kB and
 * 800×800 at most; requests accepted typically for 72 hours after the event.
 */
object PackAriss {

    const val FORMULAIRE = "https://ariss-usa.org/ARISS_SSTV/submit.php"
    const val REGLEMENT = "https://ariss.pzk.org.pl/sstv/"
    const val GALERIE = "https://www.ariss.org/upcoming-sstv-events.html"
    /** The form's limits. */
    const val MAX_OCTETS = 800 * 1024
    const val MAX_COTE = 800

    /** One picture of the pack. */
    class Entree(
        val shot: SstvMeta.SstvShot,
        /** The PNG as kept (never changed). */
        val original: ByteArray,
        val largeur: Int, val hauteur: Int,
        /** Its QSL version (PNG or JPEG), null when it could not be drawn. */
        val qsl: ByteArray?, val qslNom: String,
        val infos: QslSstv.Infos,
        val passage: SatPass?,
        /** Its mark, -1 unknown. */
        val note: Int = -1,
    )

    private fun utc(t: Long, motif: String) = SimpleDateFormat(motif, Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))

    /**
     * The station's equipment: the « Montage » plan that receives on a rig
     * or an SDR (the owner's shown first), its antennas, rotor, receivers,
     * sound cards; else the rig chosen in the settings ([poste]).
     */
    fun materiel(plans: PlansMontage.Plans?, poste: String = ""): List<String> {
        val recoit = setOf(Montage.Genre.POSTE, Montage.Genre.SDR)
        val gardes = setOf(Montage.Genre.ANTENNE, Montage.Genre.ROTOR, Montage.Genre.AMPLI, Montage.Genre.FILTRE, Montage.Genre.POSTE,
            Montage.Genre.SDR, Montage.Genre.CARTE_SON, Montage.Genre.INTERFACE)
        val l = plans?.plans.orEmpty().sortedByDescending { it.id == plans?.affiche }
        val p = l.firstOrNull { pl -> pl.chaine.elements.any { Montage.modele(it)?.genre in recoit } &&
            pl.chaine.elements.none { (Montage.modele(it)?.genre) == Montage.Genre.RECEPTEUR_DATV } }
            ?: l.firstOrNull { pl -> pl.chaine.elements.any { Montage.modele(it)?.genre in recoit } }
        val noms = p?.chaine?.elements?.filter { Montage.modele(it)?.genre in gardes }
            ?.sortedBy { Montage.modele(it)?.genre?.colonne ?: 9 }?.map { Montage.nom(it) }?.distinct().orEmpty()
        return noms.ifEmpty { listOfNotNull(poste.takeIf { it.isNotBlank() }) }
    }

    /** The form's « general location » from a locator (its first two letters), "" unknown. */
    fun region(locator: String): String {
        val l = locator.trim().uppercase()
        if (l.length < 2 || l[0] !in 'A'..'R' || l[1] !in 'A'..'R') return ""
        val lon = (l[0] - 'A') * 20 - 180 + 10
        val lat = (l[1] - 'A') * 10 - 90 + 5
        return when {
            lat < -60 -> "Antarctica"
            lat in 35..75 && lon in -25..60 -> "Europe"
            lat in -35..37 && lon in -20..55 -> "Africa"
            lon in -170..-30 && lat >= 10 -> "North America"
            lon in -90..-30 && lat < 10 -> "South America"
            lon in 110..180 && lat in -50..-10 -> "Australia"
            lon >= 130 || lon <= -150 -> "Oceania"
            else -> "Asia"
        }
    }

    /** The summary as text (UTF-8), one block per picture. */
    fun resume(l: List<Entree>, materiel: List<String>, maintenant: Long): String {
        val sb = StringBuilder()
        val premier = l.firstOrNull()?.infos
        sb.append(t("ariss_titre")).append('\n').append("=".repeat(t("ariss_titre").length)).append("\n\n")
        sb.append(tf("ariss_fait", utc(maintenant, "dd/MM/yyyy HH:mm") + " UTC")).append("\n\n")
        if (premier != null) {
            sb.append(t("ariss_station")).append('\n')
            sb.append("  ").append(t("ariss_indicatif")).append(" : ").append(premier.indicatif.ifBlank { "SWL" }).append('\n')
            sb.append("  ").append(t("ariss_locator")).append(" : ").append(premier.locator).append('\n')
            region(premier.locator).takeIf { it.isNotBlank() }?.let { sb.append("  ").append(t("ariss_region")).append(" : ").append(it).append('\n') }
            if (materiel.isNotEmpty()) sb.append("  ").append(t("ariss_materiel")).append(" : ").append(materiel.joinToString(", ")).append('\n')
            sb.append('\n')
        }
        for ((k, e) in l.withIndex()) {
            val i = e.infos
            sb.append(tf("ariss_image", k + 1, l.size)).append(" — ").append(e.shot.fileName).append('\n')
            fun ligne(cle: String, v: String) { if (v.isNotBlank()) sb.append("  ").append(t(cle)).append(" : ").append(v).append('\n') }
            ligne("ariss_date", if (i.quandMs > 0) utc(i.quandMs, "yyyy-MM-dd") else "")
            ligne("ariss_heure", if (i.quandMs > 0) utc(i.quandMs, "HH:mm:ss") + " UTC" else "")
            ligne("ariss_satellite", i.satNom)
            ligne("ariss_frequence", QslSstv.frequence(i.freqHz, i.modulation))
            ligne("ariss_mode", i.mode)
            ligne("ariss_serie", i.serie)
            ligne("ariss_diapo", if (i.diapo > 0) i.diapo.toString() + "/" + (if (i.diapos > 0) i.diapos else 12) else "")
            e.passage?.let { p ->
                ligne("ariss_aos", utc(p.aosEpochMs, "HH:mm:ss") + " UTC")
                ligne("ariss_los", utc(p.losEpochMs, "HH:mm:ss") + " UTC")
                ligne("ariss_el_max", Math.round(p.maxElevationDeg).toString() + "°")
            }
            ligne("ariss_el", i.elDeg?.let { Math.round(it).toString() + "°" } ?: "")
            ligne("ariss_note", if (e.note >= 0) e.note.toString() + "/100" else "")
            ligne("ariss_complete", if (e.shot.complete) t("ariss_oui") else t("ariss_partielle"))
            ligne("ariss_taille", e.largeur.toString() + "×" + e.hauteur + " px, " + (e.original.size / 1024) + " kB" +
                if (e.original.size > MAX_OCTETS || e.largeur > MAX_COTE || e.hauteur > MAX_COTE) " — " + t("ariss_trop_grande") else "")
            ligne("ariss_fichiers", "original/" + e.shot.fileName + (if (e.qsl != null) ", qsl/" + e.qslNom else ""))
            sb.append('\n')
        }
        sb.append(t("ariss_a_faire")).append('\n')
        sb.append("  1. ").append(tf("ariss_etape_1", FORMULAIRE)).append('\n')
        sb.append("  2. ").append(t("ariss_etape_2")).append('\n')
        sb.append("  3. ").append(t("ariss_etape_3")).append('\n')
        sb.append("  4. ").append(t("ariss_etape_4")).append("\n\n")
        sb.append(t("ariss_regles")).append('\n')
        sb.append("  - ").append(t("ariss_regle_1")).append('\n')
        sb.append("  - ").append(t("ariss_regle_2")).append('\n')
        sb.append("  - ").append(t("ariss_regle_3")).append('\n')
        sb.append("  - ").append(t("ariss_regle_4")).append('\n')
        sb.append("  ").append(t("ariss_sources")).append(" : ").append(REGLEMENT).append(" ; ").append(FORMULAIRE).append(" ; ").append(GALERIE).append('\n')
        sb.append("  ").append(t("ariss_verifier")).append('\n')
        return sb.toString()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** The same summary as a page (the pictures shown from the pack's folders). */
    fun resumeHtml(l: List<Entree>, materiel: List<String>, maintenant: Long): String {
        val texte = resume(l, materiel, maintenant)
        val sb = StringBuilder("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<title>").append(esc(t("ariss_titre"))).append("</title><style>body{font:15px/1.5 system-ui,sans-serif;max-width:760px;margin:24px auto;padding:0 16px;background:#fff;color:#111}")
        sb.append("img{max-width:100%;border:1px solid #ccc;margin:6px 0}pre{white-space:pre-wrap;background:#f5f5f2;padding:12px;border-radius:6px}figure{margin:14px 0}</style></head><body>")
        sb.append("<h1>").append(esc(t("ariss_titre"))).append("</h1>")
        for (e in l) {
            sb.append("<figure><img src=\"original/").append(esc(e.shot.fileName)).append("\" alt=\"").append(esc(e.shot.fileName)).append("\">")
            if (e.qsl != null) sb.append("<img src=\"qsl/").append(esc(e.qslNom)).append("\" alt=\"QSL\">")
            sb.append("<figcaption>").append(esc(e.shot.fileName)).append("</figcaption></figure>")
        }
        sb.append("<pre>").append(esc(texte)).append("</pre>")
        sb.append("<p><a href=\"").append(FORMULAIRE).append("\">").append(FORMULAIRE).append("</a></p></body></html>")
        return sb.toString()
    }

    /** The pack's name: « SatMe_ARISS_F4IOZ_20261024.zip ». */
    fun nomFichier(indicatif: String, maintenant: Long): String =
        "SatMe_ARISS_" + indicatif.uppercase().replace(Regex("[^A-Z0-9-]"), "-").trim('-').ifBlank { "SWL" } + "_" + utc(maintenant, "yyyyMMdd_HHmm") + ".zip"

    /** The pack: original/…, qsl/…, LISEZMOI.txt (the summary), resume.html. */
    fun zip(l: List<Entree>, materiel: List<String>, maintenant: Long): ByteArray {
        require(l.isNotEmpty())
        val o = ByteArrayOutputStream()
        ZipOutputStream(o).use { z ->
            fun met(nom: String, b: ByteArray) { z.putNextEntry(ZipEntry(nom).apply { time = maintenant }); z.write(b); z.closeEntry() }
            val vus = HashSet<String>()
            for (e in l) {
                if (!vus.add(e.shot.fileName)) continue
                met("original/" + e.shot.fileName, e.original)
                e.qsl?.let { met("qsl/" + e.qslNom, it) }
            }
            met(t("ariss_fichier_resume"), resume(l, materiel, maintenant).toByteArray(Charsets.UTF_8))
            met("resume.html", resumeHtml(l, materiel, maintenant).toByteArray(Charsets.UTF_8))
        }
        return o.toByteArray()
    }
}
