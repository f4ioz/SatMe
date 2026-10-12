/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat

import android.content.Context
import fr.f4ioz.satcombo.usb.PortsSerieAndroid

/** The Icom CI-V driver on the phone's USB ports. */
fun CivController(context: Context): CivController = CivController(PortsSerieAndroid(context))

/** The pair of rigs (FT-817, IC-705, TS-2000, TH-D72) on the phone's USB ports. */
fun Ft817Pair(context: Context): Ft817Pair =
    Ft817Pair(PortsSerieAndroid(context)) { m -> runCatching { android.util.Log.i("SatMeTs2000", m) } }

/** The TH-D72 alone (its panel, a probe) on the phone's USB ports. */
fun Thd72Lien(context: Context): Thd72Lien = Thd72Lien(PortsSerieAndroid(context))
