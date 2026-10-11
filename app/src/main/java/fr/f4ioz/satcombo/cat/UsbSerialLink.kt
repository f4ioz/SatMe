/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat

import com.hoho.android.usbserial.driver.UsbSerialPort

/** The real serial line, over the USB cable. */
class UsbSerialLink(private val port: UsbSerialPort) : SerialLink {
    override fun write(bytes: ByteArray, timeoutMs: Int): Boolean =
        runCatching { port.write(bytes, timeoutMs) }.isSuccess

    override fun read(buf: ByteArray, timeoutMs: Int): Int =
        runCatching { port.read(buf, timeoutMs) }.getOrDefault(0)

    override fun close() { runCatching { port.close() } }
}
