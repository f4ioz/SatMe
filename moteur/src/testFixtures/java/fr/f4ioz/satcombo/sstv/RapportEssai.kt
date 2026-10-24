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
import fr.f4ioz.satcombo.domain.CampagneSstv
import fr.f4ioz.satcombo.station.DessinMontage
import fr.f4ioz.satcombo.station.FonctionsMontage
import fr.f4ioz.satcombo.station.Montage
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.sin

/**
 * An event's report as a week of ARISS gives one, for the report's tests
 * (the page, the PDF): four days, twenty passes high and low, nine slides of
 * twelve (read, by hand, estimated), copies, a pass with nothing, the
 * autopilot's word, the chain — and real JPEGs for the sheet and the slides.
 */
object RapportEssai {

    private const val J = 86_400_000L
    /** 22/10/2026 15:10 UTC. */
    const val T0 = 1_792_681_800_000L

    private fun jpeg(w: Int, h: Int, teinte: Float, texte: String): ByteArray {
        val b = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = b.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.paint = GradientPaint(0f, 0f, Color.getHSBColor(teinte, 0.7f, 0.9f), w.toFloat(), h.toFloat(), Color.getHSBColor(teinte + 0.3f, 0.8f, 0.35f))
        g.fillRect(0, 0, w, h)
        g.color = Color(255, 255, 255, 200)
        for (i in 0 until 6) g.fillOval(w * i / 6, h / 3 + (h / 8 * sin(i.toDouble())).toInt(), w / 10, w / 10)
        g.font = g.font.deriveFont(h / 7f)
        g.drawString(texte, w / 20, h - h / 12)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(b, "jpg", it) }.toByteArray()
    }

    private fun passe(aos: Long, minutes: Int, el: Double, azA: Double, azL: Double): SatPass {
        val n = minutes * 2
        val tr = (0 until n).map { i -> val u = (i + 0.5) / n; (azA + (azL - azA + 360) % 360 * u) % 360 to el * sin(Math.PI * u) }
        return SatPass("ISS", 25544, aos, aos + minutes * 60_000L, el, azA, azL, sunlit = false, nightAtObserver = false, track = tr)
    }

    private fun shot(nom: String, t: Long, complete: Boolean = true) =
        SstvMeta.SstvShot(fileName = nom, satName = "ISS", timeMs = t, mode = "PD120", complete = complete)

    /** The owner's plan « IC-9700 ISS SSTV » as he drew it: pieces placed by hand, his photo shown on the rig. */
    const val PLAN = """{"v":1,"id":"p2","nom":"IC-9700 ISS SSTV","elements":[{"x":0,"y":5,"id":"e1","m":"yagi-2m","nom":""},
        {"x":0,"y":114,"id":"e2","m":"yagi-70cm","nom":""},{"x":25,"y":237,"id":"e3","m":"rotor-gs232","nom":""},
        {"x":291,"ph":true,"y":46,"id":"e6","m":"ic9700","nom":""},{"x":564,"y":35,"id":"e9","m":"pc","nom":""}],
        "liens":[{"de":"e3.moteur","note":"","c":"ROTOR","vers":"e1.mat","id":"l4"},{"de":"e2.mat","note":"","c":"ROTOR","vers":"e3.moteur","id":"l5"},
        {"de":"e1.ant","note":"25 m","c":"COAX_PL","vers":"e6.ant144","id":"l7"},{"de":"e2.ant","note":"25m","c":"COAX_N","vers":"e6.ant432","id":"l8"},
        {"de":"e6.usb","note":"","c":"USB","vers":"e9.usb","id":"l10"}]}"""

    /** A cut-out photo (transparent PNG): a dark box with a screen, as a rig's front. */
    fun photo(): DessinMontage.Photo {
        val b = BufferedImage(220, 120, BufferedImage.TYPE_INT_ARGB)
        val g = b.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0x23, 0x28, 0x2E); g.fillRoundRect(4, 10, 212, 100, 14, 14)
        g.color = Color(0x0F, 0x3A, 0x44); g.fillRect(50, 24, 90, 48)
        g.color = Color(0x55, 0x5F, 0x6A); g.fillOval(160, 40, 44, 44)
        g.dispose()
        return DessinMontage.Photo(ByteArrayOutputStream().also { ImageIO.write(b, "png", it) }.toByteArray(), 220, 120)
    }

    /** SatMe's logo, as a small PNG would be. */
    fun logo(): ByteArray {
        val b = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val g = b.createGraphics()
        g.color = Color(0x0E, 0x16, 0x26); g.fillRoundRect(0, 0, 64, 64, 18, 18)
        g.color = Color(0x38, 0xE1, 0xD4); g.fillRect(14, 26, 12, 14); g.fillRect(38, 26, 12, 14)
        g.color = Color(0xEA, 0xF1, 0xFF); g.fillRect(27, 28, 10, 9)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(b, "png", it) }.toByteArray()
    }

    /** The plan lit for the series: the 70 cm Yagi, the IC-9700 by its USB sound, SSTV decoding. */
    fun montage(photos: Boolean = true): DessinMontage.Dessin = DessinMontage.pourSstv("IC-9700 ISS SSTV", Montage.lit(PLAN), CampagneSstv.Chaine(), 437.396,
        FonctionsMontage.essai("sstv-carte"), if (photos) mapOf("e6" to photo()) else emptyMap())

    /** The report, made on day 3 (the last day's passes still to come); with [montage] the chain drawn as Station › Montage draws it, and the logo. */
    fun rapport(avecChaine: Boolean = true, montage: Boolean = false): RapportEvenement.Rapport {
        val ev = EvenementSstv.Evenement("c:serie-34", EvenementSstv.Genre.CAMPAGNE, "ARISS série 34 — « Les femmes dans l'espace »",
            "ISS", 25544, T0, T0 + 4 * J, 158.97)
        // Five passes a day, every 93 minutes, high and low.
        val els = listOf(12.0, 47.0, 71.0, 23.0, 6.0, 9.0, 38.0, 82.0, 31.0, 4.0, 15.0, 55.0, 64.0, 18.0, 3.0, 11.0, 42.0, 77.0, 27.0, 5.0)
        val passes = els.mapIndexed { i, el ->
            val aos = T0 + (i / 5) * J + 2 * 3_600_000L + (i % 5) * 93 * 60_000L
            passe(aos, if (el > 30) 10 else 7, el, (230.0 + i * 37) % 360, (110.0 + i * 23) % 360)
        }
        fun dans(p: Int, s: Int) = passes[p].aosEpochMs + 60_000L + s * 159_000L
        val noms = mutableListOf<SstvMeta.SstvShot>()
        fun im(nom: String, p: Int, s: Int, complete: Boolean = true) = shot(nom, dans(p, s) + 120_000L, complete).also { noms += it }
        val gs = listOf(
            ClassementSstv.Groupe("g1", listOf(im("s1a.png", 1, 0), im("s1b.png", 6, 1)), 1, 12, ClassementSstv.Origine.LU, mapOf("s1a.png" to 91, "s1b.png" to 74)),
            ClassementSstv.Groupe("g2", listOf(im("s2.png", 1, 1)), 2, 12, ClassementSstv.Origine.LU, mapOf("s2.png" to 86)),
            ClassementSstv.Groupe("g3", listOf(im("s3.png", 1, 2, complete = false)), 3, 12, ClassementSstv.Origine.ESTIME, mapOf("s3.png" to 48)),
            ClassementSstv.Groupe("g4", listOf(im("s4.png", 2, 0)), 4, 12, ClassementSstv.Origine.MAIN, mapOf("s4.png" to 79)),
            ClassementSstv.Groupe("g5", listOf(im("s5.png", 2, 1), im("s5b.png", 7, 3)), 5, 12, ClassementSstv.Origine.LU, mapOf("s5.png" to 95, "s5b.png" to 60)),
            ClassementSstv.Groupe("g6", listOf(im("s6.png", 2, 2)), 6, 12, ClassementSstv.Origine.LU, mapOf("s6.png" to 83)),
            ClassementSstv.Groupe("g8", listOf(im("s8.png", 7, 0)), 8, 12, ClassementSstv.Origine.ESTIME, mapOf("s8.png" to 66)),
            ClassementSstv.Groupe("g9", listOf(im("s9.png", 11, 0)), 9, 12, ClassementSstv.Origine.LU, mapOf("s9.png" to 88)),
            ClassementSstv.Groupe("g11", listOf(im("s11.png", 12, 1)), 11, 12, ClassementSstv.Origine.LU, mapOf("s11.png" to 72)),
            ClassementSstv.Groupe("gx", listOf(im("sx.png", 12, 3, complete = false)), 0, 12, ClassementSstv.Origine.AUCUNE, mapOf("sx.png" to 22)),
        )
        val modele = HoraireDiapos.ajuste(listOf(HoraireDiapos.Repere(dans(1, 0), 1), HoraireDiapos.Repere(dans(2, 1), 5), HoraireDiapos.Repere(dans(11, 0), 9)), 12, 158.97)
        val parPasse = noms.map { s -> s to (passes.firstOrNull { s.timeMs in it.aosEpochMs..it.losEpochMs + 120_000L }?.aosEpochMs ?: 0L) }
        val prev = SerieSstv.Prevision(ev.serie(), gs, modele, null, setOf(1, 2, 3, 4, 5, 6, 8, 9, 11), 10.0, parPasse)
        val traces = listOf(
            CampagneSstv.Trace("serie-34", ev.nom, 25544, "ISS", passes[1].aosEpochMs, passes[1].losEpochMs, 47.0, CampagneSstv.Statut.FAIT, ""),
            CampagneSstv.Trace("serie-34", ev.nom, 25544, "ISS", passes[8].aosEpochMs, passes[8].losEpochMs, 31.0, CampagneSstv.Statut.ECARTE, "rotor occupé"))
        val vignettes = gs.flatMap { it.images }.mapIndexed { i, s -> s.fileName to jpeg(320, 256, i / 11f, s.fileName.removeSuffix(".png")) }.toMap()
        return RapportEvenement.construit(ev, prev, passes, RapportEvenement.Station("F4IOZ", "JN18FS", "Olivier"), 10.0, T0 + 3 * J + 3_600_000L,
            positions = null, geometrie = RapportEvenement.Geometrie(true, T0 - J), traces = traces,
            chaine = if (avecChaine) listOf(listOf(RapportEvenement.Etape("Antennes", "Yagi 2 m 5 él."), RapportEvenement.Etape("Antennes", "Colinéaire")),
                listOf(RapportEvenement.Etape("Postes", "IC-9700")), listOf(RapportEvenement.Etape("Son", "Carte USB du poste"))) else null,
            nomChaine = "IC-9700 satellites", pilote = listOf("poste accordé par CAT (Doppler)", "rotor suivant le satellite", "fréquence : 145.800 MHz FM"),
            planche = jpeg(1600, 1131, 0.6f, "Planche SSTV · série 34"), nomPlanche = "Série 34",
            vignette = { vignettes[it] },
            montage = if (avecChaine && montage) montage() else null, logo = if (montage) logo() else null)
    }
}
