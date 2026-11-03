/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.aprs

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The phone's iGate settings: off at first start, the passcode kept apart from the backed-up settings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IGateHubTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @After fun apres() { runCatching { IGateHub.setActif(app, false) } }

    @Test
    fun `coupe au premier demarrage, une trame entendue ne fait rien`() {
        IGateHub.init(app)
        assertEquals("coupe", IGateHub.etat.value.phase)
        IGateHub.entendu(Trame(Adresse.de("APRS"), Adresse.de("Q1AAA"), emptyList(), ">bonjour".toByteArray()))
        assertEquals(0, IGateHub.etat.value.transmis)
        assertTrue(IGateHub.etat.value.refuses.isEmpty())
    }

    @Test
    fun `le code APRS-IS reste hors des reglages sauvegardes`() {
        IGateHub.posePasscode(app, "12345")
        assertTrue(IGateHub.passcodePose(app))
        val prefs = app.getSharedPreferences("satcombo_settings", Context.MODE_PRIVATE).all
        assertFalse(prefs.values.any { it?.toString()?.contains("12345") == true })
        assertEquals("12345", File(app.noBackupFilesDir, "igate-passcode").readText())
        IGateHub.effacePasscode(app)
        assertFalse(IGateHub.passcodePose(app))
    }
}
