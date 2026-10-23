/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import fr.f4ioz.satcombo.data.ActivitesEnLigne.Reponse
import fr.f4ioz.satcombo.data.ActivitesEnLigne.Src
import fr.f4ioz.satcombo.domain.ActivitesAVenir.Confiance
import fr.f4ioz.satcombo.domain.ActivitesAVenir.Type
import fr.f4ioz.satcombo.domain.LectureActivites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The sources fetched at their pace, conditionally, kept on disk, failures said quietly. */
class ActivitesEnLigneTest {

    private fun ressource(n: String) = javaClass.getResource("/activites/$n")!!.readText()
    private var maintenant = LectureActivites.iso("2026-10-23T20:00:00Z")!!
    private val demandes = ArrayList<String>()
    private var enPanne = false

    private val faux = ActivitesEnLigne.Telechargeur { url, etag, _ ->
        demandes += url
        if (enPanne) throw java.io.IOException("réseau absent")
        when {
            url.contains("calendar.google.com") -> if (etag == "\"v1\"") Reponse(304, null) else Reponse(200, ressource("ariss-extrait.ics"), "\"v1\"")
            url.contains("amsat-uk.org") -> Reponse(200, ressource("amsat-uk.rss"))
            url.contains("amsat-f.org") -> Reponse(200, "<!DOCTYPE html><html>redirigé</html>")
            url.contains("norad_cat_id=57166") -> Reponse(200, ressource("satnogs-57166.json"))
            url.contains("norad_cat_id=59051") -> Reponse(200, "[]")
            url.contains("sat_info.php") -> Reponse(200, ressource("amsat-iss-sstv.json"))
            else -> Reponse(404, null)
        }
    }

    private fun dossier(): File = Files.createTempDirectory("satme-activites").toFile()

    @Test fun tout_lu_fusionne_confirme_et_garde_sur_disque() {
        val d = dossier()
        val a = ActivitesEnLigne(d, { maintenant }, faux)
        assertTrue(a.rafraichit(ActivitesEnLigne.Reglages()))
        val l = a.liste(ActivitesEnLigne.Reglages())
        val s = l.single { it.type == Type.SSTV }
        assertEquals(listOf("ARISS (agenda)", "AMSAT-UK", "AMSAT (écoutes)"), s.sources.map { it.nom })
        assertEquals(Confiance.CONFIRME, s.confiance)
        assertTrue(l.any { it.type == Type.LRPT && it.catnum == 57166 })
        // Each source once; AMSAT asked for the ISS only (the SSTV window is open).
        assertEquals(6, demandes.size)
        assertEquals(1, demandes.count { it.contains("name=ISS_%5BSSTV%5D") })
        // AMSAT-F answered a web page: an error, said, not an empty success.
        assertTrue(a.etats().single { it.src == Src.AMSAT_F }.erreur.isNotEmpty())
        // Offline, the next start: the same list from the file.
        val b = ActivitesEnLigne(d, { maintenant }, { _, _, _ -> throw AssertionError("pas de réseau attendu") })
        assertEquals(l, b.liste(ActivitesEnLigne.Reglages()))
        d.deleteRecursively()
    }

    @Test fun chaque_source_a_son_rythme_et_le_calendrier_par_get_conditionnel() {
        val d = dossier()
        val a = ActivitesEnLigne(d, { maintenant }, faux)
        val r = ActivitesEnLigne.Reglages()
        a.rafraichit(r)
        demandes.clear()
        maintenant += 29 * 60_000L
        a.rafraichit(r)
        assertEquals(emptyList<String>(), demandes)   // nothing due yet
        maintenant += 2 * 60_000L
        a.rafraichit(r)
        assertEquals(1, demandes.size)   // AMSAT's reports, every 30 min while the SSTV is on
        demandes.clear()
        maintenant += 6 * 3_600_000L
        a.rafraichit(r)
        assertTrue(demandes.any { it.contains("calendar.google.com") })
        assertTrue(demandes.any { it.contains("amsat-uk.org") })
        assertFalse(demandes.any { it.contains("satnogs") })   // twice a day
        // 304: still read, its list kept.
        assertEquals("", a.etats().single { it.src == Src.ARISS }.erreur)
        assertTrue(a.liste(r).any { it.type == Type.SSTV })
        d.deleteRecursively()
    }

    @Test fun une_panne_garde_la_liste_et_la_derniere_reussite() {
        val d = dossier()
        val a = ActivitesEnLigne(d, { maintenant }, faux)
        val r = ActivitesEnLigne.Reglages()
        a.rafraichit(r)
        val avant = a.liste(r)
        val succes = a.etats().single { it.src == Src.ARISS }.succesMs
        enPanne = true
        maintenant += 7 * 3_600_000L
        a.rafraichit(r)
        val e = a.etats().single { it.src == Src.ARISS }
        assertTrue(e.erreur.contains("réseau absent"))
        assertEquals(succes, e.succesMs)
        assertEquals(avant, a.liste(r))
        d.deleteRecursively()
    }

    @Test fun ce_qui_est_coupe_n_est_pas_demande() {
        val d = dossier()
        val a = ActivitesEnLigne(d, { maintenant }, faux)
        assertFalse(a.rafraichit(ActivitesEnLigne.Reglages(actif = false)))
        assertEquals(emptyList<String>(), demandes)
        a.rafraichit(ActivitesEnLigne.Reglages(rss = false, satnogs = false, amsat = false))
        assertEquals(listOf(true), demandes.map { it.contains("calendar.google.com") }.distinct())
        assertEquals(emptyList<Any>(), a.liste(ActivitesEnLigne.Reglages(actif = false)))
        d.deleteRecursively()
    }
}
