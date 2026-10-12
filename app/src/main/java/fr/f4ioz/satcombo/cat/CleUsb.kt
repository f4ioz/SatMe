/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat

/**
 * A device's key: serial number if any, else vendor/product plus USB tree position.
 *
 * File-level because both classes need it (single rig to open, pair to list and
 * probe); a key computed two different ways would designate nothing.
 */
internal fun cleDe(dev: android.hardware.usb.UsbDevice): String =
    IdentiteUsb.cle(
        runCatching { dev.serialNumber }.getOrNull(),
        dev.vendorId, dev.productId, dev.deviceName)
