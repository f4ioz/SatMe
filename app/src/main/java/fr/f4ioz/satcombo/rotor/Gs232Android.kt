/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.rotor

import android.content.Context
import fr.f4ioz.satcombo.usb.PortsSerieAndroid

/** The GS-232 rotator driver on the phone's USB ports. */
fun Gs232Rotor(context: Context): Gs232Rotor = Gs232Rotor(PortsSerieAndroid(context))
