/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import java.awt.Desktop
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * SatMe-PC started from the desktop (Windows: its icon, no console): the
 * page opened in the browser, an icon by the clock to open it again or quit,
 * and a second start only opens the page of the one already running.
 */
object BureauPc {

    /** Is a SatMe-PC already answering on [port] of this PC? */
    fun dejaLance(port: Int): Boolean = runCatching {
        val c = URL("http://127.0.0.1:$port/api/capacites").openConnection() as HttpURLConnection
        c.connectTimeout = 800; c.readTimeout = 1500
        c.inputStream.use { String(it.readBytes()) }.contains("onglets")
    }.getOrDefault(false)

    fun ouvre(url: String) {
        runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url)) }
    }

    /** The icon by the clock: the page, the folder of settings and recordings, quit. */
    fun icone(url: String, dossier: File) {
        if (!SystemTray.isSupported()) return
        runCatching {
            val image = MarquePc.icone ?: return
            val menu = PopupMenu()
            fun entree(texte: String, action: () -> Unit) = menu.add(MenuItem(texte).apply { addActionListener { action() } })
            entree("Ouvrir SatMe-PC") { ouvre(url) }
            entree("Dossier (réglages, enregistrements)") { runCatching { Desktop.getDesktop().open(dossier) } }
            menu.addSeparator()
            entree("Quitter SatMe-PC") { System.exit(0) }
            val t = TrayIcon(image, "SatMe-PC — $url", menu).apply {
                isImageAutoSize = true
                addActionListener { ouvre(url) }
            }
            SystemTray.getSystemTray().add(t)
        }
    }
}
