/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import fr.f4ioz.satcombo.station.FonctionsMontage.Instantane
import fr.f4ioz.satcombo.station.FonctionsMontage.Statut
import fr.f4ioz.satcombo.station.FonctionsMontage.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The IC-9700 over the network in the « Montage » views: its sound, its LAN link, its antenna. */
class FonctionsMontageReseauTest {

    private val CODEC = "USB PCM2902 Audio CODEC [plughw:1,0]"
    private val RESEAU = FonctionsMontage.SOURCE_RESEAU

    /**
     * A QO-100 station like the owner's: dish, twin LNB, MiniTiouner,
     * down-converter and Pluto; the IC-9700's 70 cm to the up-converter and
     * PA; the rig on the PC by USB only (no LAN cable drawn), no 2 m / 70 cm antenna.
     */
    private fun stationQo100(): Montage.Chaine = Montage.Bati("Station QO-100").run {
        val d = ajoute("parabole-qo100"); val lnb = ajoute("lnb-twin"); val mt = ajoute("minitiouner"); val dn = ajoute("dxp-down-mk4")
        val p = ajoute("pluto"); val r = ajoute("ic9700"); val up = ajoute("dxp-up-mk4"); val pa = ajoute("dxp-pa-20w"); val pc = ajoute("pc")
        relie(d, "lnb", lnb, "entree"); relie(lnb, "sortie1", mt, "lnb_a"); relie(lnb, "sortie2", dn, "entree"); relie(dn, "sortie", p, "rx")
        relie(r, "ant432", up, "entree"); relie(up, "sortie", pa, "entree"); relie(pa, "sortie", d, "patch")
        relie(mt, "usb", pc, "usb"); relie(p, "usb", pc, "usb"); relie(r, "usb", pc, "usb")
        chaine()
    }

    /** SSTV on the rig's network sound, the CAT over the LAN. */
    private val reseau = Instantane(sonPresent = true, cartes = listOf(CODEC, RESEAU), libelles = mapOf(CODEC to "IC-9700 (USB Audio CODEC)"), carteDuPoste = CODEC,
        catConnecte = true, catModele = "IC9700", sonActif = true, sonAppareil = RESEAU, sstvActif = true, sstvSource = "carte", sstvSourceActive = "carte",
        posteReseau = true, reseauEtat = "CONNECTE", reseauLatenceMs = 12, reseauCat = true, reseauSon = true, posteMhz = 145.800)

    private fun id(c: Montage.Chaine, m: String) = c.elements.first { it.modele == m }.id

    // ------------------------------------------------------------------ 1. the network source is the rig's sound

    @Test fun laSourceReseauEstLeSonDuPoste() {
        assertTrue(reseau.estSonDuPoste(RESEAU)); assertTrue(reseau.estSonDuPoste(CODEC)); assertFalse(reseau.estSonDuPoste("HDA Intel PCH"))
        assertEquals(RESEAU, reseau.sonDuPoste)
        val v = FonctionsMontage.vue("sstv-rx", stationQo100(), reseau)!!
        // No « the rig's card is … » nor a button to take the USB codec.
        assertTrue(v.besoins.map { it.texte }.toString(), v.besoins.none { it.texte.contains("carte du poste") || it.texte.contains("Le son du poste") })
        assertTrue(v.besoins.none { it.action?.cle == "son.entree" })
        assertEquals("IC-9700 (réseau)", v.noeud("f:son")!!.detail)
        // The rig's sound over the LAN, said on its edge.
        assertTrue(v.arcs.any { it.vers == "f:son" && it.texte == "son réseau" })
        // In the software half, the rig to its network source.
        val g = FonctionsMontage.logiciel(reseau)
        val n = g.noeuds.first { it.nom == RESEAU }
        assertTrue(g.arcs.any { it.de == "f:poste" && it.vers == n.id && it.texte == "réseau" })
        assertTrue(g.arcs.any { it.de == "pc" && it.vers == n.id && it.type == Type.LAN })
    }

    @Test fun uneAutreCarteProposeLeSonReseauDuPoste() {
        val i = reseau.copy(sonAppareil = "HDA Intel PCH [plughw:0,0]", cartes = reseau.cartes + "HDA Intel PCH [plughw:0,0]")
        val v = FonctionsMontage.vue("sstv-rx", stationQo100(), i)!!
        val b = v.besoins.single { it.action?.cle == "son.entree" }
        assertEquals(RESEAU, b.action!!.valeur)
        // Off the network, the USB codec stays the rig's own sound.
        val usb = i.copy(posteReseau = false)
        assertEquals(CODEC, usb.sonDuPoste)
        assertEquals(CODEC, FonctionsMontage.vue("sstv-rx", stationQo100(), usb)!!.besoins.single { it.action?.cle == "son.entree" }.action!!.valeur)
    }

    // ------------------------------------------------------------------ 2. the link as it goes

    @Test fun sansCableLanLaLiaisonReseauEstDetecteeEtProposee() {
        val c = stationQo100()
        val v = FonctionsMontage.vue("sstv-rx", c, reseau)!!
        val pc = "e:" + id(c, "pc"); val r = "e:" + id(c, "ic9700")
        val lan = v.arcs.single { it.de == r && it.vers == pc }
        assertEquals(Type.LAN, lan.type); assertTrue(lan.detecte)
        assertTrue(lan.texte, lan.texte.startsWith("réseau (LAN) · 12 ms")); assertEquals("CAT + son", lan.sous)
        // The SatMe-PC node is there, between the rig and the sound.
        assertEquals("SatMe-PC", v.noeud(pc)!!.nom)
        val ed = v.besoins.mapNotNull { it.edition }.single { it.params["op"] == "relie" }
        assertEquals("Ajouter ce câble", ed.texte)
        assertEquals(id(c, "ic9700") + ".lan", ed.params["de"]); assertEquals(id(c, "pc") + ".lan", ed.params["vers"]); assertEquals("ETHERNET", ed.params["c"])
        // The cable added as offered: the link is the drawn one now, by the LAN, not the USB.
        val op = EditionMontage.lit { ed.params[it].orEmpty() }!!
        val c2 = EditionMontage.applique(c, op).chaine
        val v2 = FonctionsMontage.vue("sstv-rx", c2, reseau)!!
        val lan2 = v2.arcs.single { it.de == r && it.vers == pc }
        assertEquals(Type.LAN, lan2.type); assertFalse(lan2.detecte); assertEquals(Statut.OK, lan2.statut)
        assertTrue(v2.besoins.none { it.edition?.params?.get("op") == "relie" })
        // Over USB, the old audio edge.
        val usb = FonctionsMontage.vue("sstv-rx", c2, reseau.copy(posteReseau = false, sonAppareil = CODEC))!!
        assertEquals(Type.AUDIO, usb.arcs.single { it.de == r && it.vers == pc }.type)
    }

    @Test fun laVueCompleteVoitLeCableLanAbsentEtGardeLePc() {
        val c = stationQo100()
        val a = ActiviteMontage.essai("sstv-reseau")
        val al = ActiviteMontage.allume(c, a)
        val d = al.detectes.single()
        assertEquals(Montage.Bout(id(c, "ic9700"), "lan"), d.de); assertEquals(Montage.Bout(id(c, "pc"), "lan"), d.vers)
        assertEquals(Montage.Cable.ETHERNET, d.cable)
        // SatMe-PC stays in the simplified drawing.
        assertTrue(id(c, "pc") in al.enOeuvre())
        // Once drawn, nothing more is seen missing.
        val c2 = EditionMontage.applique(c, EditionMontage.Op.Relie(d.de, d.vers, d.cable)).chaine
        assertTrue(ActiviteMontage.allume(c2, a).detectes.isEmpty())
    }

    // ------------------------------------------------------------------ 3. the antenna

    @Test fun lAntenneSAjouteEnUnClicEtLaChaineVaDeLAntenneAuxImages() {
        val c = stationQo100()
        val v = FonctionsMontage.vue("sstv-rx", c, reseau)!!
        val b = v.besoins.first { it.texte.startsWith("Aucune antenne") }
        assertNotNull(b.edition)
        val ed = b.edition!!
        assertEquals("antenne", ed.params["op"])
        assertTrue(ed.texte, ed.texte.startsWith("Ajouter une antenne 2 m / 70 cm"))
        val r = EditionMontage.applique(c, EditionMontage.lit { ed.params[it].orEmpty() }!!)
        assertTrue(r.erreur, r.ok)
        val ant = r.chaine.element(r.id!!)!!
        assertEquals("yagi-bibande", ant.modele)
        // The 70 cm port feeds the up-converter: only the 2 m one is cabled.
        assertTrue(r.chaine.liens.any { it.de == Montage.Bout(ant.id, "ant144") && it.vers == Montage.Bout(id(c, "ic9700"), "ant144") })
        assertTrue(r.chaine.liens.none { it.de.element == ant.id && it.de.port == "ant432" })
        // Plus the LAN cable: antenna → rig → network → PC → sound → decoder → images.
        val c3 = EditionMontage.applique(r.chaine, EditionMontage.Op.Relie(Montage.Bout(id(c, "ic9700"), "lan"), Montage.Bout(id(c, "pc"), "lan"))).chaine
        val v3 = FonctionsMontage.vue("sstv-rx", c3, reseau)!!
        assertEquals(listOf("yagi-bibande", "ic9700", "pc", "f:son", "f:sstv-rx", "x:ecran"), v3.noeuds.sortedBy { it.colonne }.map { it.modele ?: it.id })
        assertEquals(listOf(Type.RF, Type.LAN, Type.AUDIO, Type.AUDIO, Type.RESEAU), v3.arcs.map { it.type })
        assertTrue(v3.besoins.none { it.texte.startsWith("Aucune antenne") })
        assertEquals("En marche de bout en bout", v3.resume)
        // No free port left: the fix is no longer offered.
        assertTrue(EditionMontage.antennesLibres(r.chaine, id(c, "ic9700")).isEmpty())
    }

    @Test fun leSatelliteSuiviChoisitLaBandeDeLAntenne() {
        val c = stationQo100().let { EditionMontage.applique(it, EditionMontage.Op.Antenne(id(it, "ic9700"))).chaine }
        // 2 m (145.800): the dual-band Yagi by the 2 m port.
        val v2 = FonctionsMontage.vue("sstv-rx", c, reseau)!!
        assertTrue(v2.noeuds.any { it.modele == "yagi-bibande" })
        // 70 cm (437.614): its port feeds the up-converter, said so; no antenna drawn on 2 m for it.
        val v70 = FonctionsMontage.vue("sstv-rx", c, reseau.copy(posteMhz = 437.614))!!
        assertTrue(v70.noeuds.none { it.modele == "yagi-bibande" })
        val b = v70.besoins.first { it.texte.startsWith("Aucune antenne") }
        assertTrue(b.texte, b.texte.contains("70 cm") && b.texte.contains("437,614") && b.texte.contains("ANT 430 MHz"))
        // Nothing left to cable on the rig: no button.
        assertNull(b.edition)
    }

    @Test fun lAntenneSeRefuseSansPortLibreOuSurAutreChoseQuUnPoste() {
        val c = stationQo100()
        assertFalse(EditionMontage.applique(c, EditionMontage.Op.Antenne(id(c, "pluto"))).ok)
        val une = EditionMontage.applique(c, EditionMontage.Op.Antenne(id(c, "ic9700"))).chaine
        assertFalse(EditionMontage.applique(une, EditionMontage.Op.Antenne(id(c, "ic9700"))).ok)
    }

    // ------------------------------------------------------------------ 4. SatMe-PC in every drawing

    /** The owner's chain as kept (14 pieces, three drawn by their photo, the IC-9700 by USB only). */
    private val PROPRIETAIRE = """{"v": 1, "elements": [{"id": "e1", "m": "parabole-qo100", "nom": ""}, {"id": "e2", "m": "lnb-twin", "nom": "", "o": {"ref": "1"}}, {"id": "e3", "m": "minitiouner", "nom": ""}, {"ph": true, "id": "e4", "m": "dxp-down-mk4", "nom": ""}, {"id": "e5", "m": "pluto", "nom": ""}, {"ph": true, "id": "e6", "m": "ic9700", "nom": ""}, {"id": "e7", "m": "dxp-up-mk4", "nom": ""}, {"id": "e8", "m": "dxp-pa-20w", "nom": ""}, {"id": "e9", "m": "antenne-gps", "nom": ""}, {"ph": true, "id": "e10", "m": "dxp-gpsdo", "nom": ""}, {"id": "e11", "m": "antenne-gps", "nom": ""}, {"id": "e12", "m": "leo-bodnar", "nom": ""}, {"id": "e13", "m": "alim-138", "nom": ""}, {"id": "e14", "m": "pc", "nom": ""}], "nom": "Station QO-100", "liens": [{"de": "e1.lnb", "note": "", "c": "DIRECT", "vers": "e2.entree", "id": "l1"}, {"de": "e2.sortie1", "note": "", "c": "COAX_F", "vers": "e3.lnb_a", "id": "l2"}, {"de": "e2.sortie2", "note": "", "c": "COAX_F", "vers": "e4.entree", "id": "l3"}, {"de": "e4.sortie", "note": "", "c": "COAX_SMA", "vers": "e5.rx", "id": "l4"}, {"de": "e6.ant432", "note": "", "c": "COAX_N", "vers": "e7.entree", "id": "l5"}, {"de": "e7.sortie", "note": "", "c": "COAX_SMA", "vers": "e8.entree", "id": "l6"}, {"de": "e8.sortie", "note": "", "c": "COAX_N", "vers": "e1.patch", "id": "l7"}, {"de": "e9.gps", "note": "", "c": "GPS", "vers": "e10.gps", "id": "l8"}, {"de": "e10.s1", "note": "", "c": "REFERENCE", "vers": "e7.ref", "id": "l9"}, {"de": "e10.s2", "note": "", "c": "REFERENCE", "vers": "e4.ref", "id": "l10"}, {"de": "e11.gps", "note": "", "c": "GPS", "vers": "e12.gps", "id": "l11"}, {"de": "e12.s1", "note": "", "c": "REFERENCE", "vers": "e2.ref25", "id": "l12"}, {"de": "e13.dc", "note": "", "c": "ALIM", "vers": "e8.alim", "id": "l13"}, {"de": "e13.dc", "note": "", "c": "ALIM", "vers": "e7.alim", "id": "l14"}, {"de": "e13.dc", "note": "", "c": "ALIM", "vers": "e4.alim", "id": "l15"}, {"de": "e13.dc", "note": "", "c": "ALIM", "vers": "e10.alim", "id": "l16"}, {"de": "e3.usb", "note": "", "c": "USB", "vers": "e14.usb", "id": "l17"}, {"de": "e5.usb", "note": "", "c": "USB", "vers": "e14.usb", "id": "l18"}, {"de": "e6.usb", "note": "", "c": "USB", "vers": "e14.usb", "id": "l19"}, {"de": "e12.usb", "note": "", "c": "USB", "vers": "e14.usb", "id": "l20"}]}"""

    /** The rig by the network and nothing else: what the owner's station did on 20/10. */
    private val posteSeul = ActiviteMontage.Activite(rx = setOf("cat"), notes = mapOf("cat" to "réseau · 2 ms · CAT + son"), liaisons = mapOf("cat" to "reseau"))

    @Test fun lePcDuProprietaireResteDansLeDessinSimplifie() {
        val c = Montage.lit(PROPRIETAIRE)
        assertEquals(14, c.elements.size)
        val al = ActiviteMontage.allume(c, posteSeul)
        // Regression: the PC (e14) fell out of the simplified drawing, a « SatMe-PC — pas dans le montage » drawn over the rig's photo.
        assertTrue(al.enOeuvre().toString(), "e14" in al.enOeuvre() && "e6" in al.enOeuvre())
        assertEquals("e14", DispositionMontage.dispose(c, al.enOeuvre()).boites.single { Montage.modele(c.element(it.id)!!)?.genre == Montage.Genre.ORDINATEUR }.id)
        // Its LAN cable, not drawn, is seen and offered.
        assertEquals(Montage.Bout("e6", "lan"), al.detectes.single().de)
    }

    @Test fun aucunePieceNeSeChevaucheCompletOuSimplifieAvecOuSansPhotos() {
        val avec = Montage.lit(PROPRIETAIRE)
        val sans = avec.copy(elements = avec.elements.map { it.copy(photo = false) })
        val toutes = avec.copy(elements = avec.elements.map { it.copy(photo = true) })
        for (c in listOf(avec, sans, toutes)) for (garde in listOf(null, ActiviteMontage.allume(c, posteSeul).enOeuvre(), ActiviteMontage.allume(c, ActiviteMontage.essai("rx")).enOeuvre())) {
            val b = DispositionMontage.dispose(c, garde).boites
            if (garde != null) assertEquals(garde, b.map { it.id }.toSet())
            for (x in b.indices) for (y in x + 1 until b.size) {
                val p = b[x]; val q = b[y]
                val chevauche = p.x < q.x + q.l && q.x < p.x + p.l && p.y < q.y + q.h && q.y < p.y + p.h
                assertFalse("${p.id} et ${q.id} se chevauchent (${garde ?: "complet"})", chevauche)
            }
        }
    }
}
