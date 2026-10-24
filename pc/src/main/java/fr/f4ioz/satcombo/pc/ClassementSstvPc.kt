/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.demo.ApiClassementSstv
import fr.f4ioz.satcombo.domain.CampagneSstv
import fr.f4ioz.satcombo.sstv.ClassementGalerie
import fr.f4ioz.satcombo.sstv.EvenementSstv
import fr.f4ioz.satcombo.sstv.SerieSstv
import org.json.JSONObject
import java.io.File
import javax.imageio.ImageIO

/**
 * The SSTV gallery sorted on the PC (« Images reçues » by slide, the
 * sheet's « Remplir »), and the series under way: its window from
 * « Activités à venir » (else an Agenda › SSTV campaign), its passes from
 * the station's predictor (worked out again every 10 minutes).
 */
class ClassementSstvPc(
    private val sstv: SstvPc,
    private val station: StationPc,
    private val activites: () -> ActivitesPc?,
    private val autopilote: () -> AutoPiloteSstvPc?,
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    val galerie = ClassementGalerie(::pixels)

    private fun pixels(f: File): ClassementGalerie.Pixels? = runCatching {
        val b = ImageIO.read(f) ?: return null
        ClassementGalerie.Pixels(b.getRGB(0, 0, b.width, b.height, null, 0, b.width), b.width, b.height)
    }.getOrNull()

    private fun images() = sstv.images().filter { it.source != SstvPc.ESSAI }

    /** The gallery sorted now (measures still missing done in the background). */
    fun etat(attendre: Boolean = false): ClassementGalerie.Etat = galerie.etat(sstv.dossier, images(), attendre)

    private var passes: Triple<String, Long, Pair<List<SatPass>, List<SatPass>>>? = null

    /** The series' passes: to come (to its end) and done (since its start), kept 10 minutes. */
    @Synchronized private fun passes(s: SerieSstv.Serie, t: Long): Pair<List<SatPass>, List<SatPass>> {
        val cle = s.catnum.toString() + ":" + s.debutMs + ":" + s.finMs
        passes?.let { (k, quand, l) -> if (k == cle && t - quand < 10 * 60_000L) return l }
        val avenir = if (s.finMs > t) runCatching { station.passagesDe(s.catnum, t - 15 * 60_000L, s.finMs) }.getOrDefault(emptyList()) else emptyList()
        val faits = runCatching { station.passagesDe(s.catnum, s.debutMs - 3_600_000L, minOf(t, s.finMs + 3_600_000L)) }.getOrDefault(emptyList())
        val l = avenir to faits
        passes = Triple(cle, t, l)
        return l
    }

    fun serie(t: Long = horloge()): SerieSstv.Serie? = SerieSstv.choisit(
        runCatching { activites()?.liste() }.getOrNull().orEmpty(), autopilote()?.campagnes.orEmpty(), t)

    /** The events a sheet may be about: the autopilot's campaigns, the SSTV activities announced. */
    fun evenements(): List<EvenementSstv.Evenement> = EvenementSstv.liste(
        runCatching { activites()?.liste() }.getOrNull().orEmpty(), autopilote()?.campagnes.orEmpty())

    /** The autopilot's history, its campaigns. */
    fun traces(): List<CampagneSstv.Trace> = autopilote()?.historique.orEmpty()
    fun campagne(id: String): CampagneSstv.Campagne? = autopilote()?.campagnes?.firstOrNull { it.id == id }

    /** `GET /api/sstv/classement`. */
    fun json(): JSONObject {
        val t = horloge()
        val e = etat()
        val s = serie(t)
        val p = s?.let {
            val (avenir, faits) = passes(it, t)
            SerieSstv.prevision(it, e, avenir, faits, SerieSstv.elMin(station.settings.minElevDeg.toDouble()), t)
        }
        return ApiClassementSstv.json(e, p, t)
    }

    /** `POST /api/sstv/diapo`, `/api/sstv/groupe`; null when not one of them. */
    fun action(route: String, p: (String) -> String): JSONObject? =
        ApiClassementSstv.action(route, p, sstv.dossier, galerie, images())
}
