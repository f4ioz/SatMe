/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.data.AdifImport
import fr.f4ioz.satcombo.domain.Indicatifs
import fr.f4ioz.satcombo.domain.Rattrapage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quick log. One rule throughout: **nothing may block an entry**.
 * Plausibility is a colour, the keypad highlights but never removes, and the
 * callsign the format rejects is exactly the one you put the antenna up for.
 */
class IndicatifsTest {

    private val jour = 86_400_000L
    private val maintenant = 1_800_000_000_000L

    private fun connu(
        call: String, n: Int, ilYAJours: Long,
        carre: String = "", sat: String = ""
    ) = Indicatifs.Connu(
        indicatif = call, contacts = n,
        dernierMs = maintenant - ilYAJours * jour,
        dernierSat = sat,
        locators = if (carre.isEmpty()) emptyList()
        else listOf(Indicatifs.LocatorVu(carre, n, maintenant - ilYAJours * jour)))

    // ---------------------------------------------------------- normalisation

    @Test
    fun les_suffixes_d_exploitation_se_detachent() {
        assertEquals("F4HRJ" to "/P", Indicatifs.separe("f4hrj/p"))
        assertEquals("F4HRJ" to "/MM", Indicatifs.separe("F4HRJ/MM"))
        assertEquals("F4HRJ" to "", Indicatifs.separe(" f4hrj "))
    }

    /**
     * `FG/F4IOZ` has a prefix, not a suffix: a different entity. Merging it
     * with `F4IOZ` would mix two countries in the stats and suggest the
     * mainland grid to an operator in Guadeloupe.
     */
    @Test
    fun un_prefixe_de_pays_n_est_pas_un_suffixe() {
        assertEquals("FG/F4IOZ" to "", Indicatifs.separe("FG/F4IOZ"))
        assertEquals("FG/F4IOZ", Indicatifs.cle("FG/F4IOZ"))
        assertEquals("FG/F4IOZ" to "/P", Indicatifs.separe("FG/F4IOZ/P"))
        // The key keeps the suffix: FG/F4IOZ/P is an entry of its own, with its
        // own grid squares.
        assertEquals("FG/F4IOZ/P", Indicatifs.cle("FG/F4IOZ/P"))
    }

    /**
     * Country prefix **and** operating suffix (`LA/DF2ET/P`, `TF/M0NKC/P`…).
     * The old parser required exactly two parts, so the portable station
     * inherited the home grid — the one grid known to be wrong.
     */
    @Test
    fun un_prefixe_de_pays_et_un_suffixe_coexistent() {
        assertEquals("LA/DF2ET" to "/P", Indicatifs.separe("LA/DF2ET/P"))
        assertEquals("TF/M0NKC" to "/P", Indicatifs.separe("TF/M0NKC/P"))
        assertEquals("EA6/DF2ET" to "/P", Indicatifs.separe("ea6/df2et/p"))
        assertEquals("EA5/F4IOZ" to "/P", Indicatifs.separe("EA5/F4IOZ/P"))
        // The key keeps prefix AND suffix: each operation is its own entry,
        // with its own grid squares.
        assertEquals("LA/DF2ET/P", Indicatifs.cle("LA/DF2ET/P"))
    }

    @Test
    fun un_portable_a_prefixe_n_herite_pas_du_carre() {
        // The guard is in the key, not in locatorPropose: typing LA/DF2ET/P does
        // NOT find the LA/DF2ET entry, so nothing is suggested, with no special
        // rule.
        val memoire = Indicatifs.memoire(listOf(
            Indicatifs.Contact("LA/DF2ET", "JP99", 10L)))
        val trouve = memoire.firstOrNull { it.indicatif == Indicatifs.cle("LA/DF2ET/P") }
        assertEquals(null, trouve)
        assertEquals("", Indicatifs.locatorPropose(trouve, "LA/DF2ET/P"))
    }

    /** Maritime forms from a real log: `UT1FG/MM`, `PA3GAN/MM`. */
    @Test
    fun le_suffixe_maritime_se_detache_aussi() {
        assertEquals("UT1FG" to "/MM", Indicatifs.separe("UT1FG/MM"))
        assertEquals("PA3GAN" to "/MM", Indicatifs.separe("PA3GAN/MM"))
    }

    /**
     * What is not an operating suffix stays attached: `SM/UA1CBX`,
     * `EA5/PA3GAN`, `4X/OM2IB` are other entities, not other operating modes.
     */
    @Test
    fun un_prefixe_seul_ne_se_detache_jamais() {
        assertEquals("SM/UA1CBX" to "", Indicatifs.separe("SM/UA1CBX"))
        assertEquals("EA5/PA3GAN" to "", Indicatifs.separe("EA5/PA3GAN"))
        assertEquals("4X/OM2IB" to "", Indicatifs.separe("4X/OM2IB"))
    }

    @Test
    fun la_touche_barre_fait_defiler_les_suffixes() {
        assertEquals("/P", Indicatifs.suffixeSuivant(""))
        assertEquals("/M", Indicatifs.suffixeSuivant("/P"))
        assertEquals("/MM", Indicatifs.suffixeSuivant("/M"))
        assertEquals("", Indicatifs.suffixeSuivant("/MM"))
    }

    @Test
    fun le_prefixe_prend_les_lettres_puis_les_chiffres() {
        assertEquals("F4", Indicatifs.prefixe("F4HRJ"))
        assertEquals("DL1", Indicatifs.prefixe("DL1ABC"))
        assertEquals("9A3", Indicatifs.prefixe("9A3XYZ"))
        assertEquals("2E0", Indicatifs.prefixe("2E0ABC"))
        assertEquals("3DA0", Indicatifs.prefixe("3DA0XX"))
        assertEquals("F4", Indicatifs.prefixe("F4HRJ/P"))
    }

    // ---------------------------------------------------------- plausibility

    @Test
    fun un_indicatif_ordinaire_est_plausible() {
        listOf("F4HRJ", "DL1ABC", "9A3XY", "W1AW", "VK9XZ", "F4HRJ/P").forEach {
            assertTrue("$it devrait passer", Indicatifs.plausible(it))
        }
    }

    @Test
    fun ce_qui_n_a_ni_chiffre_ni_lettre_finale_ne_l_est_pas() {
        listOf("ABCDE", "12345", "F4", "F4H1", "F4-HRJ").forEach {
            assertFalse("$it ne devrait pas passer", Indicatifs.plausible(it))
        }
    }

    /**
     * The principle of this file: an unusual callsign stays enterable. `etat`
     * returns a colour, never a refusal, and nothing here can prevent saving.
     */
    @Test
    fun un_indicatif_inhabituel_reste_saisissable() {
        val etat = Indicatifs.etat("XYZ", emptyList())
        assertEquals(Indicatifs.Etat.INHABITUEL, etat)
        // It still enters the memory, hence the log.
        val m = Indicatifs.memoire(listOf(Indicatifs.Contact("XYZ", "JN18", maintenant)))
        assertEquals(1, m.size)
    }

    @Test
    fun l_etat_reconnait_un_correspondant_deja_contacte() {
        val m = listOf(connu("F4HRJ", 14, 3))
        assertEquals(Indicatifs.Etat.DEJA_CONTACTE, Indicatifs.etat("F4HRJ", m))
        assertEquals(Indicatifs.Etat.DEJA_CONTACTE, Indicatifs.etat("F4HRJ/P", m))
        assertEquals(Indicatifs.Etat.PLAUSIBLE, Indicatifs.etat("F4XYZ", m))
        assertEquals(Indicatifs.Etat.VIDE, Indicatifs.etat("  ", m))
    }

    // ------------------------------------------------------------ suggestions

    /**
     * The roadmap scenario as is: type three characters, the station with
     * fourteen contacts comes first.
     */
    @Test
    fun trois_caracteres_suffisent_a_faire_remonter_le_bon() {
        val m = listOf(
            connu("F4HRJ", 14, 5, "JN18"),
            connu("F4HKA", 3, 40, "JN25"),
            connu("F4HTZ", 1, 400, "IN97"),
            connu("DL1ABC", 9, 2, "JO31"))
        val s = Indicatifs.suggestions("F4H", m, maintenant)
        assertEquals(3, s.size)
        assertEquals("F4HRJ", s[0].indicatif)
        assertTrue(s.none { it.indicatif == "DL1ABC" })
    }

    /** Under two characters everything looks alike: suggest nothing. */
    @Test
    fun une_seule_lettre_ne_declenche_aucune_proposition() {
        val m = listOf(connu("F4HRJ", 14, 5))
        assertTrue(Indicatifs.suggestions("F", m, maintenant).isEmpty())
        assertTrue(Indicatifs.suggestions("", m, maintenant).isEmpty())
    }

    /**
     * At most three suggestions. Beyond that the line needs reading instead
     * of a glance, and the seconds gained are lost again.
     */
    @Test
    fun jamais_plus_de_trois_propositions() {
        val m = (1..20).map { connu("F4H%02d".format(it).replace("0", "A"), it, 1) }
        assertTrue(Indicatifs.suggestions("F4H", m, maintenant).size <= 3)
    }

    @Test
    fun la_recence_departage_a_frequence_egale() {
        val m = listOf(connu("F4HAA", 5, 2), connu("F4HBB", 5, 800))
        assertEquals("F4HAA", Indicatifs.suggestions("F4H", m, maintenant)[0].indicatif)
    }

    @Test
    fun la_frequence_pese_plus_qu_un_seul_contact_recent() {
        val m = listOf(connu("F4HAA", 40, 30), connu("F4HBB", 1, 1))
        assertEquals("F4HAA", Indicatifs.suggestions("F4H", m, maintenant)[0].indicatif)
    }

    /**
     * The current satellite is the best short-term hint during a pass: all
     * else equal, the station already heard on RS-44 comes first.
     */
    @Test
    fun le_satellite_actif_departage() {
        val m = listOf(
            connu("F4HAA", 5, 10, sat = "SO-50"),
            connu("F4HBB", 5, 10, sat = "RS-44"))
        assertEquals("F4HBB",
            Indicatifs.suggestions("F4H", m, maintenant, satActif = "RS-44")[0].indicatif)
    }

    @Test
    fun le_classement_est_stable_a_note_egale() {
        val m = listOf(connu("F4HBB", 5, 10), connu("F4HAA", 5, 10))
        val s = Indicatifs.suggestions("F4H", m, maintenant)
        assertEquals(listOf("F4HAA", "F4HBB"), s.map { it.indicatif })
    }

    // --------------------------------------------------------------- keypad

    /**
     * Highlight, never remove: `suitesConnues` returns letters that extend
     * something known, and the keypad enlarges those keys — all others stay
     * usable.
     */
    @Test
    fun les_suites_connues_ne_sont_qu_une_mise_en_valeur() {
        val m = listOf(connu("F4HRJ", 1, 1), connu("F4HKA", 1, 1), connu("F4IOZ", 1, 1))
        assertEquals(setOf('R', 'K'), Indicatifs.suitesConnues("F4H", m))
        assertEquals(setOf('H', 'I'), Indicatifs.suitesConnues("F4", m))
        // Nothing known extends it: empty set, the keypad stays whole — the
        // rare DX never worked before.
        assertTrue(Indicatifs.suitesConnues("ZZ9", m).isEmpty())
    }

    // -------------------------------------------------------------- locators

    /**
     * The case that matters most. `/P` says the station has moved: inheriting
     * the grid would confidently log the one grid known to be wrong — and a
     * pre-filled field is accepted without being read.
     */
    @Test
    fun un_suffixe_portable_n_herite_jamais_du_carre() {
        // Same guard, via the key: F4HRJ/P does not find F4HRJ, so does not
        // inherit JN18.
        val memoire = Indicatifs.memoire(listOf(
            Indicatifs.Contact("F4HRJ", "JN18", 3L)))
        assertEquals("JN18", Indicatifs.locatorPropose(
            memoire.firstOrNull { it.indicatif == Indicatifs.cle("F4HRJ") }, "F4HRJ"))
        assertEquals("", Indicatifs.locatorPropose(
            memoire.firstOrNull { it.indicatif == Indicatifs.cle("F4HRJ/P") }, "F4HRJ/P"))
        assertEquals("", Indicatifs.locatorPropose(null, "F4HRJ"))
    }

    @Test
    fun le_carre_propose_est_le_plus_frequent() {
        // This test once enshrined "most recent wins" — which let three bad
        // contacts override seventeen good ones. Now the most frequent wins:
        // JN07 with four contacts.
        val c = Indicatifs.Connu(
            "F4ABC", 7, maintenant,
            locators = listOf(
                Indicatifs.LocatorVu("JN07", 4, maintenant - 90 * jour),
                Indicatifs.LocatorVu("JN18", 1, maintenant - 2 * jour),
                Indicatifs.LocatorVu("IN98", 2, maintenant - 300 * jour)))
        assertEquals("JN07", c.locatorPrincipal)
    }

    @Test
    fun la_memoire_separe_la_base_et_l_exploitation_suffixee() {
        // Third form of this test. The first let the /P grid leak into the base;
        // the second discarded the /P grid; this one separates: two entries,
        // each with its own grid squares.
        val m = Indicatifs.memoire(listOf(
            Indicatifs.Contact("F4ABC", "JN07", maintenant - 90 * jour),
            Indicatifs.Contact("F4ABC", "JN07", maintenant - 80 * jour),
            Indicatifs.Contact("F4ABC", "IN95", maintenant - 40 * jour),
            Indicatifs.Contact("F4ABC/P", "JN18", maintenant - 2 * jour)))
        assertEquals(2, m.size)
        val base = m.first { it.indicatif == "F4ABC" }
        val portable = m.first { it.indicatif == "F4ABC/P" }
        assertEquals(3, base.contacts)
        assertEquals("JN07", base.locatorPrincipal)
        assertEquals(1, portable.contacts)
        assertEquals("JN18", portable.locatorPrincipal)
    }

    @Test
    fun la_memoire_ignore_les_contacts_sans_indicatif() {
        val m = Indicatifs.memoire(listOf(
            Indicatifs.Contact("", "JN18", maintenant),
            Indicatifs.Contact("  ", "JN18", maintenant),
            Indicatifs.Contact("F4ABC", "", maintenant)))
        assertEquals(1, m.size)
        assertTrue(m[0].locators.isEmpty())
    }

    /**
     * The station's name, kept on import: recognising a first name at a glance
     * beats re-reading five characters.
     */
    @Test
    fun le_nom_du_correspondant_est_retenu() {
        val m = Indicatifs.memoire(listOf(
            Indicatifs.Contact("F5RRO", "JN33AF", maintenant, "FO-29", "Olivier Commeau")))
        assertEquals("Olivier Commeau", m[0].nom)
    }

    /**
     * Not every log line has a name, and the latest contact may be the one
     * without: keep the first name seen, whatever its date.
     */
    @Test
    fun un_contact_recent_sans_nom_n_efface_pas_le_nom_connu() {
        val m = Indicatifs.memoire(listOf(
            Indicatifs.Contact("F5RRS", "JN33", maintenant - 100 * jour, "FO-29", "Damien"),
            Indicatifs.Contact("F5RRS", "JN33", maintenant, "RS-44", "")))
        assertEquals("Damien", m[0].nom)
        assertEquals("RS-44", m[0].dernierSat)
    }

    @Test
    fun un_correspondant_sans_nom_ne_produit_pas_de_texte_parasite() {
        val m = Indicatifs.memoire(listOf(
            Indicatifs.Contact("F4ABC", "JN18", maintenant)))
        assertEquals("", m[0].nom)
    }

    @Test
    fun le_dernier_satellite_est_celui_du_contact_le_plus_recent() {
        val m = Indicatifs.memoire(listOf(
            Indicatifs.Contact("F4ABC", "JN18", maintenant - 10 * jour, "SO-50"),
            Indicatifs.Contact("F4ABC", "JN18", maintenant - jour, "RS-44")))
        assertEquals("RS-44", m[0].dernierSat)
    }
}

/** Locating contacts in a recording, for both recording sources. */
class RattrapageTest {

    private val debut = 1_800_000_000_000L

    @Test
    fun un_contact_se_situe_dans_la_bande() {
        assertEquals(90_000L, Rattrapage.position(debut + 90_000L, debut))
        assertTrue(Rattrapage.dansLaBande(debut + 90_000L, debut, 720_000L))
        assertFalse(Rattrapage.dansLaBande(debut + 900_000L, debut, 720_000L))
    }

    /**
     * A recording started late gives a negative position. It must show:
     * silently clamping to zero would play the start of the recording while
     * expecting a contact that is not there.
     */
    @Test
    fun un_contact_anterieur_a_la_bande_rend_une_position_negative() {
        assertTrue(Rattrapage.position(debut - 5_000L, debut) < 0)
        assertFalse(Rattrapage.dansLaBande(debut - 5_000L, debut, 720_000L))
    }

    /**
     * A single alignment point syncs an external recorder. A pass lasts about
     * twelve minutes and a voice recorder drifts far less than a second in
     * that time: one marker aligns the whole file.
     */
    @Test
    fun un_seul_point_d_alignement_cale_tout_le_fichier() {
        val contacts = listOf(debut + 60_000L, debut + 180_000L, debut + 400_000L)
        // The operator recognises the second contact at 2:05 into the file.
        val deduit = Rattrapage.debutDeduit(contacts[1], 125_000L)
        assertEquals(debut + 55_000L, deduit)
        // The other two fall into place.
        assertEquals(5_000L, Rattrapage.position(contacts[0], deduit))
        assertEquals(345_000L, Rattrapage.position(contacts[2], deduit))
    }

    @Test
    fun la_fenetre_d_ecoute_commence_avant_le_tampon() {
        // People speak before tapping: the lead margin must be the larger.
        val f = Rattrapage.fenetre(120_000L, 720_000L)
        assertTrue(f.first < 120_000L)
        assertTrue(f.last > 120_000L)
        assertTrue(120_000L - f.first > f.last - 120_000L)
    }

    @Test
    fun la_fenetre_ne_deborde_pas_du_fichier() {
        assertEquals(0L, Rattrapage.fenetre(2_000L, 720_000L).first)
        assertEquals(720_000L, Rattrapage.fenetre(719_000L, 720_000L).last)
    }

    /**
     * An absurd alignment is rejected. Otherwise picking the wrong file would
     * play silence while the user thinks they missed the marker.
     */
    @Test
    fun un_alignement_absurde_est_refuse() {
        val contacts = listOf(debut, debut + 600_000L)
        assertTrue(Rattrapage.alignementVraisemblable(debut - 30_000L, contacts))
        assertFalse(Rattrapage.alignementVraisemblable(debut + 3 * 86_400_000L, contacts))
        assertFalse(Rattrapage.alignementVraisemblable(debut, emptyList()))
    }

    /**
     * A handful of wrong contacts must not override an established grid. Real
     * case: 17 contacts in JN18FR, 3 more recent ones mistakenly in JN33AF.
     */
    @Test
    fun le_carre_le_plus_frequent_l_emporte_sur_le_plus_recent() {
        // Local constants: this class does not share the neighbouring class's,
        // and a test should borrow nothing from its surroundings.
        val j = 86_400_000L
        val t0 = 1_800_000_000_000L
        val vieux = t0 - 200 * j
        val recent = t0 - 2 * j
        val contacts = (1..17).map {
            Indicatifs.Contact("F5RRO", "JN18FR", vieux + it * 1000L)
        } + (1..3).map {
            Indicatifs.Contact("F5RRO", "JN33AF", recent + it * 1000L)
        }
        val c = Indicatifs.memoire(contacts).first { it.indicatif == "F5RRO" }
        assertEquals("JN18FR", c.locatorPrincipal)
    }

    /** On equal counts, the most recent wins. */
    @Test
    fun a_egalite_le_plus_recent_tranche() {
        val j = 86_400_000L
        val t0 = 1_800_000_000_000L
        val c = Indicatifs.memoire(listOf(
            Indicatifs.Contact("F9ABC", "JN18", t0 - 100 * j),
            Indicatifs.Contact("F9ABC", "IN95", t0 - 2 * j),
        )).first()
        assertEquals("IN95", c.locatorPrincipal)
}

/** ADIF import, which only feeds the predictive memory. */
class AdifImportTest {

    private fun enr(vararg champs: Pair<String, String>): String =
        champs.joinToString("") { (k, v) -> "<$k:${v.length}>$v" } + "<EOR>\n"

    @Test
    fun un_champ_se_lit_par_sa_longueur_declaree() {
        val f = AdifImport.champs("<CALL:5>F4IOZ<GRIDSQUARE:6>JN18FS<EOR>")
        assertEquals("F4IOZ", f["CALL"])
        assertEquals("JN18FS", f["GRIDSQUARE"])
    }

    /** The declared length rules, even when the value contains a space. */
    @Test
    fun une_valeur_avec_espace_se_lit_entierement() {
        val f = AdifImport.champs("<SAT_NAME:5>RS 44<CALL:5>F4IOZ<EOR>")
        assertEquals("RS 44", f["SAT_NAME"])
        assertEquals("F4IOZ", f["CALL"])
    }

    @Test
    fun le_type_facultatif_est_ignore() {
        val f = AdifImport.champs("<QSO_DATE:8:D>20260805<CALL:5>F4IOZ")
        assertEquals("20260805", f["QSO_DATE"])
    }

    /**
     * Deliberate tolerance: third-party ADIF always has something unexpected,
     * and an import failing entirely on one bad record helps nobody.
     */
    @Test
    fun une_longueur_qui_deborde_ne_fait_pas_tout_echouer() {
        val f = AdifImport.champs("<CALL:40>F4IOZ")
        assertEquals("F4IOZ", f["CALL"])
    }

    @Test
    fun l_heure_est_lue_en_temps_universel() {
        val t = AdifImport.instant("20260805", "143722")
        assertEquals(1_785_940_642_000L, t)
        // Four digits: seconds are zero.
        assertEquals(AdifImport.instant("20260805", "1437"),
            AdifImport.instant("20260805", "143700"))
        assertEquals(null, AdifImport.instant("2026080", "143722"))
        assertEquals(null, AdifImport.instant("20260805", "1"))
    }

    /** The ADIF NAME field feeds the displayed name. */
    @Test
    fun le_champ_nom_de_l_adif_est_lu() {
        val texte = "<EOH>" + enr(
            "CALL" to "F5RRO", "GRIDSQUARE" to "JN33AF", "QSO_DATE" to "20260805",
            "TIME_ON" to "1437", "PROP_MODE" to "SAT", "NAME" to "Olivier Commeau")
        val b = AdifImport.lit(texte)
        assertEquals(1, b.retenus)
        assertEquals("Olivier Commeau", b.contacts[0].nom)
    }

    @Test
    fun l_en_tete_est_ecarte() {
        val texte = "Carnet exporté par Wavelog\n<PROGRAMID:7>Wavelog<EOH>\n" +
            enr("CALL" to "F4HRJ", "GRIDSQUARE" to "JN18", "QSO_DATE" to "20260805",
                "TIME_ON" to "143722", "PROP_MODE" to "SAT", "SAT_NAME" to "RS-44")
        val b = AdifImport.lit(texte)
        assertEquals(1, b.retenus)
        assertEquals("F4HRJ", b.contacts[0].indicatif)
        assertEquals("RS-44", b.contacts[0].satellite)
    }

    /**
     * Satellite only. VHF contest stations would just push down the ones
     * you will actually hear during a pass.
     */
    @Test
    fun seuls_les_contacts_satellite_alimentent_la_prediction() {
        val texte =
            enr("CALL" to "F4HRJ", "QSO_DATE" to "20260805", "TIME_ON" to "1437",
                "PROP_MODE" to "SAT") +
            enr("CALL" to "F4XXX", "QSO_DATE" to "20260805", "TIME_ON" to "1440")
        assertEquals(1, AdifImport.lit(texte).retenus)
        assertEquals(2, AdifImport.lit(
            texte, fr.f4ioz.satcombo.domain.FiltreMoisson.TOUT).retenus)
    }

    @Test
    fun le_bilan_compte_ce_qui_a_ete_ecarte() {
        val texte =
            enr("CALL" to "F4HRJ", "QSO_DATE" to "20260805", "TIME_ON" to "1437",
                "PROP_MODE" to "SAT") +
            enr("QSO_DATE" to "20260805", "TIME_ON" to "1438", "PROP_MODE" to "SAT") +
            enr("CALL" to "F4ZZZ", "PROP_MODE" to "SAT")
        val b = AdifImport.lit(texte)
        assertEquals(3, b.enregistrementsLus)
        assertEquals(1, b.retenus)
        assertEquals(1, b.sansIndicatif)
        assertEquals(1, b.sansDate)
    }

    @Test
    fun un_fichier_vide_ne_leve_pas() {
        val b = AdifImport.lit("")
        assertEquals(0, b.enregistrementsLus)
        assertTrue(b.contacts.isEmpty())
    }

    /** End to end: a Wavelog export becomes a searchable memory. */
    @Test
    fun un_export_devient_une_memoire_interrogeable() {
        val texte = "<EOH>" +
            enr("CALL" to "F4HRJ", "GRIDSQUARE" to "JN18", "QSO_DATE" to "20260801",
                "TIME_ON" to "1000", "PROP_MODE" to "SAT", "SAT_NAME" to "RS-44") +
            enr("CALL" to "F4HRJ", "GRIDSQUARE" to "JN18", "QSO_DATE" to "20260803",
                "TIME_ON" to "1100", "PROP_MODE" to "SAT", "SAT_NAME" to "RS-44") +
            enr("CALL" to "F4HKA", "GRIDSQUARE" to "JN25", "QSO_DATE" to "20260802",
                "TIME_ON" to "1200", "PROP_MODE" to "SAT", "SAT_NAME" to "SO-50")
        val memoire = Indicatifs.memoire(AdifImport.lit(texte).contacts)
        val s = Indicatifs.suggestions("F4H", memoire, 1_785_940_642_000L)
        assertEquals("F4HRJ", s[0].indicatif)
        assertEquals(2, s[0].contacts)
        assertEquals("JN18", s[0].locatorPrincipal)
    }
    /**
     * F5RRO/P is not where F5RRO is: a grid learned during a portable outing
     * must never be suggested for the home station. Reported bug: typing F5RRO
     * suggested F5RRO/P's grid.
     */
    @Test
    fun le_carre_d_une_sortie_portable_n_alimente_pas_la_station_de_base() {
        val m = Indicatifs.memoire(listOf(
            Indicatifs.Contact("F5RRO", "IN95", 1_000L, "RS-44"),
            Indicatifs.Contact("F5RRO/P", "JN17", 2_000L, "RS-44"),
        ))
        val base = m.first { it.indicatif == "F5RRO" }
        // One grid for the base: IN95 stays IN95.
        assertEquals("IN95", base.locatorPrincipal)
        // And the portable entry suggests its own instead of staying silent.
        assertEquals("JN17", m.first { it.indicatif == "F5RRO/P" }.locatorPrincipal)
    }

    @Test
    fun une_station_connue_seulement_en_portable_propose_son_propre_carre() {
        val m = Indicatifs.memoire(listOf(
            Indicatifs.Contact("F5RRO/P", "JN17", 2_000L, "RS-44"),
        ))
        assertEquals("JN17",
            Indicatifs.locatorPropose(m.first(), "F5RRO/P"))
    }
}

    /**
     * The summary counts **distinct callsigns**, not contacts: 38 QSOs with one
     * station add a single memory entry.
     */
    @Test
    fun le_bilan_compte_les_indicatifs_distincts() {
        val texte = buildString {
            append("<EOH>\n")
            repeat(3) {
                append("<CALL:5>F5RRO<QSO_DATE:8>2026082")
                append(it + 1)
                append("<TIME_ON:6>120000<PROP_MODE:3>SAT<EOR>\n")
            }
            append("<CALL:5>F6ABC<QSO_DATE:8>20260825<TIME_ON:6>120000")
            append("<PROP_MODE:3>SAT<EOR>\n")
        }
        val b = AdifImport.lit(texte)
        assertEquals(4, b.retenus)
        assertEquals(2, b.indicatifs)
    }

    /** What is dropped, and why: no callsign or no date. */
    @Test
    fun le_bilan_compte_les_ecartes() {
        val texte = "<EOH>\n" +
            "<CALL:5>F6ABC<PROP_MODE:3>SAT<EOR>\n" +
            "<QSO_DATE:8>20260825<TIME_ON:6>120000<PROP_MODE:3>SAT<EOR>\n"
        val b = AdifImport.lit(texte)
        assertEquals(0, b.retenus)
        assertEquals(2, b.ecartes)
    }
}
