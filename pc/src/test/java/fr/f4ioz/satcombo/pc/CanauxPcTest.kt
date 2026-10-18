/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** « Décodeurs multiples »: off by default, the page's gestures, the sound heard, the list kept. */
class CanauxPcTest {

    private fun stockage() = StockageFichier(File.createTempFile("canaux", ".properties").also { it.delete(); it.deleteOnExit() })

    @Test fun `eteint par defaut, rien ne tourne`() {
        val k = CanauxPc(stockage())
        assertFalse(k.actif)
        assertTrue(k.cibles(10_489_750_000.0 to 1_058_400.0).isEmpty())
        assertFalse(k.json().getBoolean("option"))
    }

    @Test fun `ajouter sur un signal, la regle des uniques, retirer`() {
        val st = stockage()
        val k = CanauxPc(st)
        val r = k.action("/api/canaux/ajoute", mapOf("hz" to "10489525000", "classe" to "cw"))
        assertTrue(r.toString(), r.getBoolean("ok"))
        // Adding turns the option on.
        assertTrue(k.actif)
        assertEquals(10_489_524_300L, k.liste[0].cadranHz)
        assertTrue(k.action("/api/canaux/ajoute", mapOf("hz" to "10489701500", "decodeur" to "phonie")).getBoolean("ok"))
        val non = k.action("/api/canaux/ajoute", mapOf("hz" to "10489801500", "decodeur" to "phonie"))
        assertFalse(non.getBoolean("ok"))
        assertTrue(non.getString("erreur").contains("un seul"))
        // Kept, and read back by a fresh one.
        assertEquals(k.liste, CanauxPc(st).liste)
        k.action("/api/canaux/retire", mapOf("id" to "1"))
        assertEquals(listOf("phonie"), k.liste.map { it.decodeur.cle })
    }

    @Test fun `les cibles suivent l'axe du recepteur`() {
        val k = CanauxPc(stockage())
        k.action("/api/canaux/prereglage", mapOf("nom" to "ft8-cw-phonie", "ecoute" to "10489720000"))
        val c = k.cibles(10_489_700_000.0 to 1_058_400.0)
        assertEquals(listOf(-160_000.0, -178_700.0, 20_000.0), c.map { it.decalageHz })
        // Behind an inverting converter the band runs the other way.
        assertEquals(160_000.0, k.cibles(10_489_700_000.0 to -1_058_400.0)[0].decalageHz!!, 0.0)
        assertTrue(k.cibles(null).isEmpty())
    }

    @Test fun `le canal ecoute remplace le son principal, une fois par bloc`() {
        val k = CanauxPc(stockage())
        k.action("/api/canaux/prereglage", mapOf("nom" to "ft8-ft4"))
        val pcm = ShortArray(100) { it.toShort() }
        k.recoit(1, pcm, 100)
        assertNull(k.prendEcoute())
        k.action("/api/canaux/ecoute", mapOf("id" to "2", "on" to "1"))
        k.recoit(1, pcm, 100); k.recoit(2, pcm, 60)
        val e = k.prendEcoute()!!
        assertEquals(60, e.second)
        assertEquals(59.toShort(), e.first[59])
        assertNull(k.prendEcoute())
        assertTrue(k.tientSon())
    }

    @Test fun `le canal SSTV tient le decodeur et suit le calage automatique`() {
        val k = CanauxPc(stockage())
        assertFalse(k.tientSstv())
        k.action("/api/canaux/ajoute", mapOf("hz" to "10489600000", "decodeur" to "sstv"))
        assertTrue(k.tientSstv())
        assertEquals(10_489_598_100L, k.cadranSstv())
        k.decaleSstv(-120)
        assertEquals(10_489_597_980L, k.cadranSstv())
        // The option off: the main receiver feeds the decoder again.
        k.action("/api/canaux/option", mapOf("on" to "0"))
        assertFalse(k.tientSstv())
    }

    @Test fun `un geste inconnu est refuse`() {
        val k = CanauxPc(stockage())
        assertFalse(k.action("/api/canaux/emet", emptyMap()).getBoolean("ok"))
        assertFalse(k.action("/api/canaux/prereglage", mapOf("nom" to "x")).getBoolean("ok"))
    }
}
