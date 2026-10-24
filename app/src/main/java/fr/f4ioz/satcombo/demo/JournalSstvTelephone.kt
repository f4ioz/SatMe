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
import fr.f4ioz.satcombo.JournalDesPassages
import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.sstv.SstvMeta
import java.io.File

/**
 * The journal's SSTV versions and the report's panels on the phone
 * ([ApiJournalSstv], SatMe-PC's too): read through the sheet's own source
 * ([PlancheWeb.source]: its events, the gallery sorted, the corrected
 * pictures; no montage here, the simple chain), kept with the pass. One for
 * the phone's screen and the page it serves.
 */
object JournalSstvTelephone {

    @Volatile private var api: ApiJournalSstv? = null

    /** The pass's SSTV pictures as the journal's page links them: the live ones when there are, those set aside left out. */
    fun images(j: JournalDesPassages, e: JournalPassage.Entree): List<Pair<File, SstvMeta.SstvShot>> {
        val l = j.liens(listOf(e))[e.id]?.images.orEmpty()
        val direct = l.any { it.second.source == "live" }
        return l.filter { (f, s) -> (!direct || s.source == "live") && f.name !in e.masquees }
    }

    fun api(ctx: Context, j: JournalDesPassages): ApiJournalSstv = api ?: synchronized(this) {
        api ?: ApiJournalSstv(object : ApiJournalSstv.Plateforme {
            private val app = ctx.applicationContext
            override fun source() = PlancheWeb.source(app)
            override fun planches() = runCatching { PlancheWeb.rangement(app).modeles() }.getOrDefault(emptyList())
            override fun passage(id: String) = j.passages().firstOrNull { it.id == id }
            override fun enregistre(e: JournalPassage.Entree) = j.maj(e)
            override fun images(e: JournalPassage.Entree) = images(j, e)
        }).also { api = it }
    }
}
