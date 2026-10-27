/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.Adif
import fr.f4ioz.satcombo.sstv.SstvMeta
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * What [SitePc] sends for each kind, read where SatMe-PC keeps it: an SSTV
 * picture of the gallery (its PNG, its satellite, time and mode), a planche
 * drawn at 1600 px, its event's report as a PDF, a contact as an ADIF
 * record, a video of the journal's editor. The words that go with it are
 * what the site shows (satellite, UTC time, mode, event, station).
 */
class SourcesSitePc(
    private val sstv: SstvPc,
    private val planche: PlancheWebPc,
    private val carnet: CarnetPc,
    private val station: StationPc,
    /** The journal's exports (`exports/`), where its videos are. */
    private val exports: File,
) {
    private fun ici(): String = listOf(station.settings.callsign, station.settings.manualLocator).filter { it.isNotBlank() }.joinToString(" · ")
    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    fun contenu(g: SitePc.Genre, ref: String): SitePc.Contenu? = when (g) {
        SitePc.Genre.SSTV -> {
            val f = sstv.fichier(ref)?.takeIf { it.name.endsWith(".png") }
            val s = sstv.images().firstOrNull { it.fileName == ref }
            if (f == null || s == null) null else SitePc.Contenu(f.readBytes(), null, "image/png", metaSstv(s))
        }
        SitePc.Genre.PLANCHE -> planche.planches().firstOrNull { it.id == ref }?.let { m ->
            planche.image(ref, 1600)?.let { img ->
                SitePc.Contenu(PlancheRenduPc.jpeg(PlancheRenduPc.rgb(img), 0.88f), null, "image/jpeg", JSONObject().put("titre", m.nom)
                    .put("evenement", m.evenement?.nom.orEmpty()).put("utc", iso(System.currentTimeMillis())).put("station", ici()))
            }
        }
        SitePc.Genre.RAPPORT -> planche.planches().firstOrNull { it.id == ref }?.let { m ->
            val r = planche.sert("/rapport", "GET", { k -> when (k) { "id" -> ref; "format" -> "pdf"; else -> "" } }) { null }
            if (!r.statut.startsWith("200") || r.corps.isEmpty()) null
            else SitePc.Contenu(r.corps, null, "application/pdf", JSONObject().put("titre", "Rapport · " + (m.evenement?.nom ?: m.nom))
                .put("evenement", m.evenement?.nom.orEmpty()).put("utc", iso(System.currentTimeMillis())).put("station", ici()))
        }
        SitePc.Genre.QSO -> ref.toLongOrNull()?.let { t -> carnet.contacts().firstOrNull { it.timeMs == t } }?.let { e ->
            val adif = Adif.enregistrement(e, station.settings.callsign)
            if (adif.isBlank()) null else SitePc.Contenu(adif.toByteArray(Charsets.UTF_8), null, "text/plain; charset=utf-8", JSONObject()
                .put("titre", "QSO " + e.callsign + " · " + e.satName).put("satellite", e.satName).put("utc", iso(e.timeMs)).put("mode", e.mode).put("station", ici()))
        }
        SitePc.Genre.VIDEO -> File(exports, ref).takeIf { File(ref).name == ref && ref.endsWith(".mp4") && it.isFile }?.let { f ->
            // « SatMe_ISS_20261003_135119Z_passage.mp4 »: its satellite and its time.
            val p = ref.removeSuffix(".mp4").split('_')
            val quand = runCatching { java.time.LocalDateTime.parse(p[2] + p[3].removeSuffix("Z"), java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                .toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }.getOrNull()
            SitePc.Contenu(null, f, "video/mp4", JSONObject().put("titre", "Vidéo · " + p.getOrElse(1) { "" } + " · " + p.getOrElse(4) { "" })
                .put("satellite", p.getOrElse(1) { "" }).also { o -> quand?.let { o.put("utc", iso(it)) } }.put("station", ici()))
        }
    }

    private fun metaSstv(s: SstvMeta.SstvShot): JSONObject {
        val o = JSONObject().put("satellite", s.satName).put("utc", iso(s.timeMs)).put("mode", s.mode)
            .put("station", listOf(s.callsign.ifBlank { station.settings.callsign }, s.locator.ifBlank { station.settings.manualLocator }).filter { it.isNotBlank() }.joinToString(" · "))
            .put("titre", listOf(s.satName, s.mode).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { s.fileName })
        // « Campagne : ARISS octobre 2026 »: the auto-pilot's note gives its event.
        Regex("^Campagne\\s*:\\s*(.+)$").find(s.note.trim())?.let { o.put("evenement", it.groupValues[1].trim().take(80)) }
        if (s.diapo > 0) o.put("description", "Diapo " + s.diapo + (if (s.diapos > 0) "/" + s.diapos else ""))
        if (!s.complete) o.put("incomplete", true)
        return o
    }
}
