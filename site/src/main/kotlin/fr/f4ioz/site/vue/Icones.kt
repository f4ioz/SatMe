/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.vue

/**
 * The site's icons, drawn for it (a 1.7 px line on a 24 grid), and the
 * F4IOZ mark: a night square, the Earth's rim, an orbit, a satellite, the
 * waves down to the station — in the spirit of SatMe's icon, not taken
 * from it.
 */
object Icones {

    private val TRACES = mapOf(
        "home" to "M3 11l9-7 9 7M5.5 9.5V20h5v-6h3v6h5V9.5",
        "articles" to "M6 3h9l4 4v14H6zM15 3v4h4M9 12h7M9 16h7",
        "gallery" to "M3 5h18v14H3zM3 16l5-5 4 4 3-3 6 6M16.5 8.5h.01",
        "logbook" to "M6 3h12a1 1 0 0 1 1 1v17H7a2 2 0 0 1-2-2V5a2 2 0 0 1 1-2zM5 18a2 2 0 0 1 2-2h12M9 7h6M9 10.5h4",
        "satme" to "M10.5 13.5l-3 3M4 7l3-3 4 4-3 3zM13 16l3-3 4 4-3 3zM10 9l5 5M8.5 10.5l5 5M15 4.5a4.5 4.5 0 0 1 4.5 4.5",
        "sked" to "M4 5h16v15H4zM4 9.5h16M8.5 3v4M15.5 3v4M9 14.5l2 2 4-4",
        "pass" to "M3 19h18M4.5 19C6.5 9 17.5 9 19.5 19M12 12v.01",
        "orbites" to "M12 7.5a4.5 4.5 0 1 0 .01 0zM2.5 12c0-2.5 4.3-4.5 9.5-4.5s9.5 2 9.5 4.5-4.3 4.5-9.5 4.5S2.5 14.5 2.5 12zM19 8.2h.01",
        "radar" to "M12 3a9 9 0 1 0 .01 0zM12 7.5a4.5 4.5 0 1 0 .01 0zM12 12l6.4-6.4",
        "tle" to "M4 6h16M4 10h16M4 14h11M4 18h7",
        "sstv" to "M3 4.5h18v12H3zM8 20.5h8M6.5 13l3.5-3.5 3 3 2-2 2.5 2.5",
        "grid" to "M4 4h16v16H4zM4 9.33h16M4 14.66h16M9.33 4v16M14.66 4v16",
        "dx" to "M12 3a9 9 0 1 0 .01 0zM3 12h18M12 3c3.2 3.2 3.2 14.8 0 18M12 3c-3.2 3.2-3.2 14.8 0 18",
        "beacons" to "M12 11v10M8.5 21L12 11l3.5 10M8 6.5a5.5 5.5 0 0 0 0 7.5M16 6.5a5.5 5.5 0 0 1 0 7.5M5 4a9.5 9.5 0 0 0 0 12.5M19 4a9.5 9.5 0 0 1 0 12.5",
        "bands" to "M5 20v-7M10 20V6M15 20v-9M20 20V4",
        "wspr" to "M2 12c2.2-6 4.6-6 6.8 0s4.6 6 6.8 0 4.6-6 6.4-2",
        "openings" to "M3 18.5h18M6.5 18.5a5.5 5.5 0 0 1 11 0M12 5v3M5 8.5l2 1.8M19 8.5l-2 1.8",
        "station" to "M12 21V6M7 21h10M6 6h12M8.5 3v6M12 3v6M15.5 3v6",
        "lock" to "M6 11h12v10H6zM8.5 11V7.5a3.5 3.5 0 0 1 7 0V11",
        "unlock" to "M6 11h12v10H6zM8.5 11V7.5a3.5 3.5 0 0 1 6.7-1.4",
        "menu" to "M4 7h16M4 12h16M4 17h16",
        "external" to "M14 4h6v6M20 4l-9 9M18 14v6H4V6h6",
        "rss" to "M5 19h.01M5 12a7 7 0 0 1 7 7M5 5a14 14 0 0 1 14 14",
        "report" to "M6 3h9l4 4v14H6zM15 3v4h4M9 17v-3M12 17v-6M15 17v-4",
        "video" to "M3 6h13v12H3zM16 10l5-3v10l-5-3",
        "direct" to "M12 11.5a1.5 1.5 0 1 0 .01 0zM8.3 15.7a6 6 0 0 1 0-8.4M15.7 7.3a6 6 0 0 1 0 8.4M5.4 18.6a10 10 0 0 1 0-14.2M18.6 4.4a10 10 0 0 1 0 14.2",
        "reglages" to "M4 7h10M18 7h2M4 17h4M12 17h8M14 4.5v5M8 14.5v5",
    )

    /** An icon by name (an empty square for an unknown one). */
    fun svg(nom: String): String =
        "<svg class=\"ico\" viewBox=\"0 0 24 24\" aria-hidden=\"true\" focusable=\"false\"><path d=\"" + (TRACES[nom] ?: "M5 5h14v14H5z") + "\"/></svg>"

    /** The F4IOZ mark, [taille] px; [id] tells its gradient apart from another mark's on the same page. */
    fun marque(taille: Int, id: String): String = """<svg class="mark" viewBox="0 0 64 64" width="$taille" height="$taille" aria-hidden="true" focusable="false">
  <defs><linearGradient id="mk-ciel-$id" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#18234A"/><stop offset="1" stop-color="#0B1022"/></linearGradient></defs>
  <rect width="64" height="64" rx="15" fill="url(#mk-ciel-$id)"/>
  <circle cx="13" cy="12" r="1" fill="#C9D6FF" opacity=".8"/><circle cx="51" cy="9" r=".8" fill="#C9D6FF" opacity=".7"/><circle cx="25" cy="7" r=".6" fill="#C9D6FF" opacity=".6"/>
  <path d="M-4 64 Q32 30 68 64Z" fill="#2B3A86"/>
  <path d="M-4 64 Q32 30 68 64" fill="none" stroke="#6F8CFF" stroke-width="1.6" opacity=".9"/>
  <path d="M5 40 Q32 6 59 40" fill="none" stroke="#3FE0C8" stroke-width="1.6" stroke-dasharray="3 3" opacity=".75"/>
  <g transform="rotate(-28 41 20)"><rect x="28.5" y="17" width="8" height="6" rx="1" fill="#3FE0C8"/><rect x="45.5" y="17" width="8" height="6" rx="1" fill="#3FE0C8"/>
    <rect x="36.5" y="19.3" width="9" height="1.4" fill="#9AA7B4"/><rect x="38" y="15.5" width="6" height="9" rx="1.5" fill="#E8F0F7"/></g>
  <path d="M34 31 q-3 4 -1 8" fill="none" stroke="#FFB454" stroke-width="2" stroke-linecap="round"/>
  <path d="M29.5 29 q-5 6 -1.5 13" fill="none" stroke="#FFB454" stroke-width="2" stroke-linecap="round" opacity=".7"/>
  <circle cx="21" cy="47" r="2.6" fill="#FFB454"/>
</svg>"""
}
