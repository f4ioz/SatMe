/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Guard on how USB permission PendingIntents are built.
 *
 * Android 14 (API 34) throws IllegalArgumentException on a MUTABLE
 * PendingIntent carrying an implicit intent. `CivController` and `Ft817Cat`
 * did exactly that, so enabling CAT crashed the app on Android 14+ only,
 * while Android 13 devices ran the same code silently.
 *
 * No unit test can build a real PendingIntent off-device (no Robolectric
 * here). Instead we check the rule that makes the mistake impossible: a
 * single place creates these objects, and it makes the intent explicit. Any
 * new `PendingIntent.getBroadcast` call elsewhere fails this test.
 */
class UsbPermissionSourceTest {

    private fun sourceRoot(): File? =
        listOf("src/main/java", "app/src/main/java", "../app/src/main/java")
            .map { File(it) }
            .firstOrNull { it.isDirectory }

    private fun kotlinSources(root: File): List<File> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `un seul endroit fabrique le PendingIntent de permission USB`() {
        val root = sourceRoot()
        assumeTrue("sources introuvables depuis " + File(".").absolutePath, root != null)
        val guilty = kotlinSources(root!!)
            .filter { it.readText().contains("PendingIntent.getBroadcast") }
            .map { it.name }
            .sorted()
        assertEquals(
            "PendingIntent.getBroadcast ne doit vivre que dans UsbPermission.kt",
            listOf("UsbPermission.kt"), guilty)
    }

    @Test
    fun `le helper rend l'intention explicite`() {
        val root = sourceRoot()
        assumeTrue("sources introuvables", root != null)
        val helper = kotlinSources(root!!).firstOrNull { it.name == "UsbPermission.kt" }
        assertTrue("UsbPermission.kt introuvable", helper != null)
        val text = helper!!.readText()
        // The intent must be limited to our package: the only way to keep
        // FLAG_MUTABLE (UsbManager needs it to write its answer) without being
        // rejected by API 34.
        assertTrue("l'intention de permission USB doit porter setPackage(...)",
            text.contains("setPackage(ctx.packageName)"))
        assertTrue("le PendingIntent doit rester MUTABLE au-delà de l'API 31",
            text.contains("PendingIntent.FLAG_MUTABLE"))
    }

    @Test
    fun `aucune demande de permission USB ne passe hors du helper`() {
        val root = sourceRoot()
        assumeTrue("sources introuvables", root != null)
        val guilty = kotlinSources(root!!)
            .filter { it.readText().contains("um.requestPermission(") ||
                      it.readText().contains("usbManager.requestPermission(") }
            .map { it.name }
            .sorted()
        assertEquals(
            "UsbManager.requestPermission ne doit être appelé que par UsbPermission.kt",
            listOf("UsbPermission.kt"), guilty)
    }
}
