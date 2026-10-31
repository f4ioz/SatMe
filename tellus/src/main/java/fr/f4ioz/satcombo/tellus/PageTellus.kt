/*
 * TellusMe — the SatMe ecosystem's terrestrial station
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.tellus

import fr.f4ioz.satcombo.demo.PagePc

/**
 * TellusMe's page: one file of its own (resources/…/tellus/page.html), not
 * SatMe-PC's page, with SatMe-PC's design tokens ([PagePc.STYLE_JETONS]) so
 * both look like one family.
 */
object PageTellus {
    private val modele: String by lazy {
        PageTellus::class.java.getResourceAsStream("page.html")!!.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    fun html(version: String): String = modele
        .replace("/*JETONS*/", PagePc.STYLE_JETONS)
        .replace("@VERSION@", version.replace("<", ""))
}
