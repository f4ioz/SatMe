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
import fr.f4ioz.satcombo.station.FonctionsMontage.Role
import fr.f4ioz.satcombo.station.FonctionsMontage.Statut
import fr.f4ioz.satcombo.station.FonctionsMontage.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FonctionsMontageTest {

    private fun prereglage(id: String) = CatalogueMateriel.PREREGLAGES.first { it.id == id }.chaine
    private val CARTE = "USB PCM2902 Audio CODEC [plughw:1,0]"
    private val base = Instantane(sdrPresent = true, datvPresent = true, sonPresent = true, phoniePresent = true, cartes = listOf(CARTE), sonCarte = CARTE)

    /** IC-9700 behind a 10 GHz → 432 MHz converter, plus a Pluto on a bias-T and a USB sound card. */
    private fun station(): Montage.Chaine = Montage.Bati("essai").run {
        val d = ajoute("parabole-qo100"); val dc = ajoute("dc-432"); val r = ajoute("ic9700"); val pc = ajoute("pc")
        val y = ajoute("yagi-70cm"); val p = ajoute("pluto"); val pa = ajoute("pa-2400")
        relie(d, "lnb", dc, "entree"); relie(dc, "sortie", r, "ant432"); relie(r, "usb", pc, "usb")
        relie(y, "ant", p, "rx"); relie(p, "tx", pa, "entree"); relie(pa, "sortie", d, "patch"); relie(p, "usb", pc, "usb")
        chaine()
    }

    // ------------------------------------------------------------------ who holds what

    @Test fun laDatvSurLePlutoLeTientEtBloqueLeSdr() {
        val i = base.copy(datvActif = true, datvRecepteur = "pluto", datvSdrTenu = "pluto", sdrRecepteur = "pluto")
        val p = FonctionsMontage.possessions(i)
        val pluto = p.single { it.ressource == "sdr:pluto" }
        assertEquals("datv-rx", pluto.detenteur)
        assertEquals(Role.RECEPTION, pluto.role)
        assertTrue(pluto.dit(), pluto.dit().startsWith("ADALM-PLUTO → occupé par DATV réception"))
        val c = FonctionsMontage.conflits(i, p).single { it.fonction == "sdr" }
        assertTrue(c.occupe && c.grave)
        assertTrue(c.raison, c.raison.contains("occupé par DATV réception"))
        // The DATV software receiver may be stopped from here (no LNB supply on a Pluto).
        assertEquals("datv.actif", c.actions.single().cle)
        assertEquals("0", c.actions.single().valeur)
    }

    @Test fun leMiniTiounerNeSArreteJamaisDIci() {
        val i = base.copy(datvActif = true, datvRecepteur = "minitiouner", sdrRecepteur = "rtl")
        val p = FonctionsMontage.possessions(i)
        assertEquals("datv-rx", p.single { it.ressource == "datv:minitiouner" }.detenteur)
        val g = FonctionsMontage.logiciel(i, p)
        assertTrue(g.noeud("f:datv-rx")!!.actions.none { it.cle == "datv.actif" })
        assertTrue(FonctionsMontage.conflits(i, p).none { c -> c.actions.any { it.cle == "datv.actif" } })
    }

    @Test fun uneEmissionTientLaCarteEtNeSeProposePasALArret() {
        val i = base.copy(txDatv = "pluto", sdrRecepteur = "pluto", txDatvChoisi = "pluto")
        val p = FonctionsMontage.possessions(i)
        val x = p.single { it.ressource == "sdr:pluto" }
        assertEquals("datv-tx", x.detenteur); assertEquals(Role.EMISSION, x.role)
        val c = FonctionsMontage.conflits(i, p).single { it.fonction == "sdr" }
        assertTrue("never a stop of a transmission from here", c.actions.isEmpty())
        assertEquals("datv", c.raccourci!!.onglet)
        assertNotNull(i.emission())
    }

    @Test fun leSdrPrendLEntreeDesDecodeurs() {
        val i = base.copy(sdrActif = true, sdrRecepteur = "rtl", sonActif = true, sonParSdr = true, sonAppareil = "Clé SDR (rtl_tcp)",
            ft8Actif = true, sstvActif = true, sstvSource = "carte")
        val p = FonctionsMontage.possessions(i)
        val e = p.single { it.ressource == "son:entree" }
        assertEquals("sdr", e.detenteur)
        assertTrue(e.partage.contains("ft8") && e.partage.contains("sstv-rx"))
        val c = FonctionsMontage.conflits(i, p).single { it.fonction == "sstv-rx" }
        assertTrue(c.occupe)
        assertEquals(listOf("son.entree" to CARTE, "sstv.source" to "sdr"), c.actions.map { it.cle to it.valeur })
        // Every action proposed is a receive setting the profiles admit.
        FonctionsMontage.conflits(i, p).flatMap { it.actions }.forEach { assertTrue(it.cle, ProfilsFonction.admis(it.cle, it.valeur)) }
    }

    @Test fun lesDecodeursSansSonSontSignales() {
        // SSTV and APRS are on by default: without sound they wait, no alarm.
        val i = base.copy(ft8Actif = true, cwActif = true, sstvActif = true, aprsActif = true)
        val c = FonctionsMontage.conflits(i)
        assertEquals(setOf("ft8", "cw"), c.map { it.fonction }.toSet())
        assertTrue(c.none { it.grave })
        assertTrue(c.all { it.actions.single().cle == "son.entree" })
    }

    @Test fun leLogicielMontreQuiFaitDuSonEtQuiLePrend() {
        val i = base.copy(sonActif = true, sonAppareil = CARTE, ft8Actif = true, sstvActif = true, sstvSource = "carte", catConnecte = true, catModele = "IC9700")
        val g = FonctionsMontage.logiciel(i)
        val carte = g.noeuds.single { it.nom == "USB PCM2902 Audio CODEC" }
        assertEquals(Statut.OK, carte.statut)
        assertTrue(carte.badges.containsAll(listOf("materiel", "son-emet")))
        assertTrue(g.noeud("f:ft8")!!.badges.containsAll(listOf("fonction", "rx", "son-recoit")))
        assertTrue(g.noeud("f:datv-tx")!!.badges.contains("tx"))
        assertTrue(g.arcs.any { it.de == carte.id && it.vers == "f:son" && it.type == Type.AUDIO })
        assertTrue(g.arcs.any { it.de == "f:son" && it.vers == "f:ft8" && it.type == Type.AUDIO })
        assertTrue(g.arcs.any { it.de == "f:son" && it.vers == "f:sstv-rx" })
        assertTrue(g.arcs.any { it.de == "pc" && it.vers == "f:poste" && it.type == Type.CAT })
        // The idle transmitters are drawn, never lit.
        assertEquals(Statut.PRET, g.noeud("f:datv-tx")!!.statut)
    }

    // ------------------------------------------------------------------ one function's chain

    @Test fun laChaineSstvParLIc9700() {
        val i = FonctionsMontage.essai("sstv-carte")!!
        val g = FonctionsMontage.vue("sstv-rx", station(), i)!!
        val ordre = g.noeuds.sortedBy { it.colonne }.map { it.modele ?: it.id }
        assertEquals(listOf("parabole-qo100", "dc-432", "ic9700", "pc", "f:son", "f:sstv-rx", "x:ecran"), ordre)
        assertTrue(g.noeuds.all { it.statut == Statut.OK })
        val types = g.arcs.map { it.type }
        assertEquals(listOf(Type.RF, Type.RF, Type.AUDIO, Type.AUDIO, Type.AUDIO, Type.RESEAU), types)
        assertTrue(g.arcs.all { it.statut == Statut.OK })
        assertEquals("IC-9700 (USB Audio CODEC)", g.noeud("f:son")!!.detail)
        // The rig to its own card: an audio edge.
        assertTrue(g.arcs.any { it.vers == "f:son" && it.type == Type.AUDIO && it.texte == "audio" })
        assertEquals("En marche de bout en bout", g.resume)
    }

    @Test fun laCarteDuPosteEstNommeeEtRelieeAuPoste() {
        val codec = "CODEC [plughw:1,0]"; val pch = "PCH [plughw:0,0]"
        val i = base.copy(cartes = listOf(pch, codec), sonCarte = "", catConnecte = true, catModele = "IC9700",
            libelles = mapOf(codec to "IC-9700 (USB Audio CODEC)", pch to "Carte mère (ALC897)"), carteDuPoste = codec)
        val g = FonctionsMontage.logiciel(i)
        val n = g.noeuds.first { it.nom == "IC-9700 (USB Audio CODEC)" }
        assertTrue(g.noeuds.any { it.nom == "Carte mère (ALC897)" })
        val arc = g.arcs.first { it.de == "f:poste" && it.vers == n.id }
        assertEquals(Type.AUDIO, arc.type); assertEquals(Statut.PRET, arc.statut)
        // Nothing runs: the rig's card is offered first, by its name; the setting keeps the card's own name.
        val a = g.noeud("f:son")!!.actions.single()
        assertEquals("Démarrer « IC-9700 (USB Audio CODEC) »", a.texte); assertEquals(codec, a.valeur)
        // Another card running: the rig's is offered, never taken by itself.
        val autre = i.copy(sonActif = true, sonAppareil = pch, sstvActif = true, sstvSource = "carte")
        val v = FonctionsMontage.vue("sstv-rx", station(), autre)!!
        assertEquals("Carte mère (ALC897)", v.noeud("f:son")!!.detail)
        assertTrue(v.besoins.any { it.action?.valeur == codec && it.action?.cle == "son.entree" })
        assertEquals("Carte son : « IC-9700 (USB Audio CODEC) »", ProfilsFonction.dit("son.entree", codec, i.libelles))
    }

    @Test fun laChaineSstvQuandLaCarteEstPriseParLeSdr() {
        val i = FonctionsMontage.essai("sstv-carte")!!.copy(sdrActif = true, sonParSdr = true, sonAppareil = "Clé SDR (rtl_tcp)", sstvSource = "carte")
        val g = FonctionsMontage.vue("sstv-rx", station(), i)!!
        assertEquals(Statut.OCCUPE, g.noeud("f:son")!!.statut)
        assertEquals(Statut.OCCUPE, g.noeud("f:sstv-rx")!!.statut)
        assertTrue(g.besoins.any { it.action?.cle == "son.entree" && it.action?.valeur == CARTE })
        assertTrue(g.resume, g.resume.startsWith("Bloquée"))
    }

    @Test fun laChaineSdrSurUnPlutoTenuParLaDatv() {
        val i = FonctionsMontage.essai("datv-pluto")!!
        val g = FonctionsMontage.vue("sdr", station(), i)!!
        val pluto = g.noeuds.single { it.modele == "pluto" }
        assertEquals(Statut.OCCUPE, pluto.statut)
        assertTrue(pluto.detail, pluto.detail.contains("occupé par DATV réception"))
        assertEquals("yagi-70cm", g.noeuds.minByOrNull { it.colonne }!!.modele)
        assertTrue(g.besoins.first().action!!.let { it.cle == "datv.actif" && it.valeur == "0" })
        // The DATV view of the same state: the Pluto is its own, all fine.
        val d = FonctionsMontage.vue("datv-rx", station(), i)!!
        assertEquals(Statut.OK, d.noeuds.single { it.modele == "pluto" }.statut)
    }

    @Test fun laChaineDEmissionVaDuPcALAntenneSansRienDemarrer() {
        val g = FonctionsMontage.vue("datv-tx", station(), base.copy(txDatvChoisi = "pluto"))!!
        val ordre = g.noeuds.sortedBy { it.colonne }.map { it.modele ?: it.id }
        assertEquals(listOf("f:datv-tx", "pc", "pluto", "pa-2400", "parabole-qo100"), ordre)
        assertTrue(g.noeud("f:datv-tx")!!.actions.isEmpty())
        assertTrue(g.besoins.all { it.action == null })
    }

    @Test fun sansMontageLaChaineDitCeQuiManque() {
        val g = FonctionsMontage.vue("ft8", Montage.Chaine(), base)!!
        assertEquals(Statut.MANQUE, g.noeud("m:cat")!!.statut)
        assertTrue(g.besoins.any { it.texte.contains("n'est pas dans le montage") })
        assertTrue(g.besoins.any { it.action?.cle == "son.entree" })
        assertNull(FonctionsMontage.vue("inconnue", Montage.Chaine(), base))
    }

    @Test fun chaqueVueSeConstruit() {
        for (essai in FonctionsMontage.ESSAIS) for (f in FonctionsMontage.VUES) for (c in listOf(station(), Montage.Chaine(), prereglage("qo100-pluto"), prereglage("noeud-deporte"))) {
            val g = FonctionsMontage.vue(f, c, FonctionsMontage.essai(essai)!!)!!
            val ids = g.noeuds.map { it.id }
            assertEquals("$essai $f", ids.size, ids.toSet().size)
            g.arcs.forEach { assertTrue("$essai $f ${it.de}", it.de in ids && it.vers in ids) }
            g.arcs.forEach { assertTrue("$essai $f left to right", g.noeud(it.de)!!.colonne < g.noeud(it.vers)!!.colonne || it.type != Type.RF) }
            g.besoins.mapNotNull { it.action }.forEach { assertTrue(it.cle, ProfilsFonction.admis(it.cle, it.valeur)) }
            assertFalse(g.json().toString().isEmpty())
        }
    }

    @Test fun leJsonDeLaPageMarqueLesAppareilsTenus() {
        val o = FonctionsMontage.json(FonctionsMontage.essai("datv-pluto")!!, station())
        val p = o.getJSONObject("elements")
        val plutoId = station().elements.first { it.modele == "pluto" }.id
        val t = p.getJSONObject(plutoId).getJSONArray("tient").getJSONObject(0)
        assertEquals("datv-rx", t.getString("fonction"))
        assertTrue(p.getJSONObject(plutoId).getJSONArray("conflits").length() > 0)
    }
}
