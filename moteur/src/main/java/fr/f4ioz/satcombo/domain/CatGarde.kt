/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

/** When a CAT link that no longer hears the rig is opened again. */
object CatGarde {
    /** The rig silent this long (ms) though connected. */
    const val MUET_MS = 15_000L
    /** At most one reopening per this long. */
    const val ENTRE_MS = 60_000L

    fun relancer(maintenantMs: Long, ouvertMs: Long, derniereReponseMs: Long, derniereRelanceMs: Long): Boolean =
        ouvertMs > 0L && maintenantMs - ouvertMs >= MUET_MS &&
            maintenantMs - derniereReponseMs >= MUET_MS && maintenantMs - derniereRelanceMs >= ENTRE_MS
}
