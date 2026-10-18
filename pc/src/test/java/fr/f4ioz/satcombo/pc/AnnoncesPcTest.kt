/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.annonces.DetecteurAnnonces.Instantane
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnoncesPcTest {

    private class Banc(outils: Set<String> = setOf("spd-say")) {
        var t = 1_000_000L
        var etat = Instantane(0)
        val lances = ArrayList<List<String>>()
        val gains = ArrayList<Float>()
        val a = AnnoncesPc(StockageMemoire(), { etat.copy(ms = t) }, { "F4IOZ" },
            lanceur = { cmd, _ -> lances += cmd; 0 },
            trouve = { n -> if (n in outils) "/usr/bin/$n" else null },
            horloge = { t }, gainSdr = { gains += it }, demarreParole = false)
    }

    @Test fun rienQuandCoupe() {
        val b = Banc()
        b.a.tic()
        b.etat = b.etat.copy(leila = true); b.a.tic()
        assertEquals(0, b.a.file.taille())
        assertFalse(b.a.json().getBoolean("on"))
    }

    @Test fun leilaDiteParLePcLeRecepteurBaisse() {
        val b = Banc()
        b.a.regle(mapOf("on" to "1", "sortie" to "PC", "volume" to "50", "vitesse" to "1.5", "baisse" to "25"))
        b.a.tic()
        b.etat = b.etat.copy(leila = true); b.t += 1000; b.a.tic()
        assertTrue(b.a.ditProchaine { })
        val c = b.lances.single()
        assertEquals(listOf("/usr/bin/spd-say", "-w", "-l", "fr", "-r", "50", "-i", "0", "--", "LEILA : trop de puissance"), c)
        assertEquals(listOf(0.25f, 1f), b.gains)
        val d = b.a.depuis(0).single()
        assertEquals("LEILA : trop de puissance", d.getString("texte"))
        assertFalse(d.getBoolean("navigateur"))
        // The same again within 30 s: not said.
        b.etat = b.etat.copy(leila = false); b.t += 1000; b.a.tic()
        b.etat = b.etat.copy(leila = true); b.t += 1000; b.a.tic()
        assertEquals(0, b.a.file.taille())
    }

    @Test fun sansMoteurLeNavigateurParle() {
        val b = Banc(outils = emptySet())
        b.a.regle(mapOf("on" to "1", "sortie" to "PC"))
        assertEquals("", b.a.json().getString("moteurEffectif"))
        b.a.essai()
        var attendu = 0L
        assertTrue(b.a.ditProchaine { attendu = it })
        assertTrue(b.lances.isEmpty()); assertTrue(b.gains.isEmpty())
        assertTrue(attendu > 1000)
        val d = b.a.depuis(0).single()
        assertTrue(d.getBoolean("navigateur"))
        assertTrue(d.getString("texte").startsWith("Annonces SatMe : essai"))
        assertTrue(d.getString("texte").contains("Fox-trot quatre India Oscar Zoulou"))
    }

    @Test fun muetVideEtTait() {
        val b = Banc()
        b.a.regle(mapOf("on" to "1", "sortie" to "LES_DEUX"))
        b.a.essai()
        b.a.regle(mapOf("muet" to "1"))
        assertEquals(0, b.a.file.taille())
        b.a.essai()   // queued as said, but muted: nothing comes out
        b.a.regle(mapOf("muet" to "1"))
        assertFalse(b.a.ditProchaine { })
        assertTrue(b.lances.isEmpty())
    }

    @Test fun commandesDesMoteurs() {
        val b = Banc(outils = setOf("espeak-ng", "piper", "aplay"))
        val r = fr.f4ioz.satcombo.annonces.Annonces.Reglages(on = true, vitesse = 1.0, volume = 100)
        val e = b.a.moteur(r)!!
        assertEquals("espeak-ng", e.nom)   // Piper only with its model
        assertEquals(listOf("/usr/bin/espeak-ng", "-v", "fr", "-s", "175", "-a", "200", "--", "Bonjour"), b.a.commande(e, "--Bonjour", r))
        val rp = r.copy(moteur = "piper", voix = "/m/fr_FR-siwis-medium.onnx", vitesse = 2.0)
        val p = b.a.moteur(rp)!!
        assertEquals("piper", p.nom)
        val c = b.a.commande(p, "x", rp)
        assertEquals("/bin/sh", c[0])
        assertEquals(listOf("piper", "/usr/bin/piper", "/m/fr_FR-siwis-medium.onnx", "0.50", "/usr/bin/aplay"), c.drop(3))
    }

    @Test fun appelCwUneFois() {
        val s = AnnoncesPc.SuiviCw()
        assertTrue(s.nouveaux("CQ CQ ", "F4IOZ").isEmpty())
        val l = s.nouveaux("CQ CQ F4IOZ DE DL1ABC K", "F4IOZ")
        assertEquals("DL1ABC", l.single().appelant)
        assertTrue(s.nouveaux("CQ CQ F4IOZ DE DL1ABC K", "F4IOZ").isEmpty())
        assertTrue(s.nouveaux("CQ CQ F4IOZ DE DL1ABC K 599", "F4IOZ").isEmpty())
        assertEquals("EA4XX", s.nouveaux("CQ CQ F4IOZ DE DL1ABC K 599 F4IOZ DE EA4XX", "F4IOZ").single().appelant)
        // The text cut at its front (kept short): the new part found by the old tail.
        val avant = (1..12).joinToString(" ") { "MOT$it" }
        assertEquals(" OK", AnnoncesPc.nouveauCw(avant, avant.drop(10) + " OK"))
        assertEquals("NEUF", AnnoncesPc.nouveauCw(avant, "NEUF"))
    }
}
