/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Garde-fou sur la fabrication des PendingIntent de permission USB.
 *
 * L'histoire : en 18.5, le CAT arrêtait l'application dès qu'on basculait
 * l'interrupteur — mais seulement sur un téléphone en Android 14 ou plus
 * récent. `CivController` et `Ft817Cat` demandaient la permission USB avec un
 * PendingIntent MUTABLE portant une intention implicite, ce que la plateforme
 * refuse depuis l'API 34 en levant une IllegalArgumentException. Sur un
 * appareil resté en Android 13 le même code passait sans un mot, si bien que la
 * panne s'est vue pour la première fois sur un Pixel 8 alors qu'un Xiaomi
 * fonctionnait.
 *
 * Aucun essai unitaire ne peut instancier un vrai PendingIntent hors appareil,
 * et le module n'utilise pas Robolectric. Ce qu'on peut vérifier en revanche,
 * c'est la règle d'écriture qui rend la faute impossible : un seul endroit dans
 * tout le code fabrique ces objets, et cet endroit rend l'intention explicite.
 * Un futur ajout qui rappellerait `PendingIntent.getBroadcast` ailleurs — le
 * geste exact qui a causé la panne — fait tomber cet essai.
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
        // L'intention doit être limitée à notre paquet : c'est la seule façon de
        // garder FLAG_MUTABLE — dont UsbManager a besoin pour y déposer sa
        // réponse — sans tomber sous le refus de l'API 34.
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
