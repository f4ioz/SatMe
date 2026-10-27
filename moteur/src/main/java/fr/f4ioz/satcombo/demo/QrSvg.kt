/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** A QR code as SVG, drawn here (ZXing, Apache 2.0): the phone's pairing, the Vitrine's link to the club. */
object QrSvg {
    fun svg(contenu: String): String {
        val m = QRCodeWriter().encode(contenu, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 4))
        val sb = StringBuilder()
        for (y in 0 until m.height) for (x in 0 until m.width) if (m[x, y]) sb.append("M$x ${y}h1v1h-1z")
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ${m.width} ${m.height}\" shape-rendering=\"crispEdges\">" +
            "<rect width=\"100%\" height=\"100%\" fill=\"#fff\"/><path d=\"$sb\" fill=\"#000\"/></svg>"
    }
}
