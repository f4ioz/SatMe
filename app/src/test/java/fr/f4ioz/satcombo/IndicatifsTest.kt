/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
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
 * Le carnet express : ce qui se calcule, calculé au banc.
 *
 * Le fil rouge de ces essais est une règle et une seule : **rien ne doit
 * bloquer une saisie**. La plausibilité est une couleur, le clavier met en
 * valeur sans jamais retirer, et l'indicatif que le format rejette est
 * justement celui pour lequel on avait sorti l'antenne.
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
     * `FG/F4IOZ` a un préfixe, pas un suffixe : c'est une autre entité. Le
     * fondre avec `F4IOZ` mélangerait deux pays dans les statistiques et
     * proposerait le carré de la métropole à un opérateur en Guadeloupe.
     */
    @Test
    fun un_prefixe_de_pays_n_est_pas_un_suffixe() {
        assertEquals("FG/F4IOZ" to "", Indicatifs.separe("FG/F4IOZ"))
        assertEquals("FG/F4IOZ", Indicatifs.cle("FG/F4IOZ"))
        assertEquals("FG/F4IOZ" to "/P", Indicatifs.separe("FG/F4IOZ/P"))
        // La clé garde le suffixe depuis la 18.74 : FG/F4IOZ/P est une entrée
        // à part entière, avec ses propres carrés.
        assertEquals("FG/F4IOZ/P", Indicatifs.cle("FG/F4IOZ/P"))
    }

    /**
     * Préfixe de pays **et** suffixe d'exploitation à la fois.
     *
     * Le carnet réel d'Olivier en compte dix-huit sur 3763 contacts satellite :
     * `LA/DF2ET/P`, `TF/M0NKC/P`, `EA6/DF2ET/P`, `F/DF2ET/P`, `PA/DF2ET/P`.
     * L'ancienne lecture exigeait exactement deux morceaux et laissait donc
     * passer ces indicatifs en bloc, `/P` compris — de sorte que le carré de la
     * station fixe était hérité par la station portable, c'est-à-dire le seul
     * carré dont on sait qu'il est faux.
     */
    @Test
    fun un_prefixe_de_pays_et_un_suffixe_coexistent() {
        assertEquals("LA/DF2ET" to "/P", Indicatifs.separe("LA/DF2ET/P"))
        assertEquals("TF/M0NKC" to "/P", Indicatifs.separe("TF/M0NKC/P"))
        assertEquals("EA6/DF2ET" to "/P", Indicatifs.separe("ea6/df2et/p"))
        assertEquals("EA5/F4IOZ" to "/P", Indicatifs.separe("EA5/F4IOZ/P"))
        // La clé garde préfixe ET suffixe : chaque exploitation est une
        // entrée, avec ses propres carrés.
        assertEquals("LA/DF2ET/P", Indicatifs.cle("LA/DF2ET/P"))
    }

    @Test
    fun un_portable_a_prefixe_n_herite_pas_du_carre() {
        // La garde n'est plus dans locatorPropose : elle est dans la clé. En
        // tapant LA/DF2ET/P, la recherche ne trouve PAS l'entrée LA/DF2ET —
        // donc rien n'est proposé, sans qu'une règle spéciale intervienne.
        val memoire = Indicatifs.memoire(listOf(
            Indicatifs.Contact("LA/DF2ET", "JP99", 10L)))
        val trouve = memoire.firstOrNull { it.indicatif == Indicatifs.cle("LA/DF2ET/P") }
        assertEquals(null, trouve)
        assertEquals("", Indicatifs.locatorPropose(trouve, "LA/DF2ET/P"))
    }

    /** Les formes maritimes du carnet réel : `UT1FG/MM`, `PA3GAN/MM`. */
    @Test
    fun le_suffixe_maritime_se_detache_aussi() {
        assertEquals("UT1FG" to "/MM", Indicatifs.separe("UT1FG/MM"))
        assertEquals("PA3GAN" to "/MM", Indicatifs.separe("PA3GAN/MM"))
    }

    /**
     * Ce qui n'est pas un suffixe d'exploitation reste attaché : `SM/UA1CBX`,
     * `EA5/PA3GAN`, `4X/OM2IB` sont d'autres entités, pas d'autres modes de
     * fonctionnement.
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

    // ---------------------------------------------------------- plausibilité

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
     * Le point de principe du fichier. Un indicatif jugé inhabituel reste
     * saisissable : `etat` rend une couleur, jamais un refus, et aucune
     * fonction de cet objet ne peut empêcher l'enregistrement.
     */
    @Test
    fun un_indicatif_inhabituel_reste_saisissable() {
        val etat = Indicatifs.etat("XYZ", emptyList())
        assertEquals(Indicatifs.Etat.INHABITUEL, etat)
        // Il entre quand même dans la mémoire, donc dans le carnet.
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
     * Le scénario de la feuille de route, tel quel : on tape trois caractères,
     * le correspondant des quatorze contacts arrive en tête.
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

    /** Sous deux caractères, tout ressemble à tout : on ne propose rien. */
    @Test
    fun une_seule_lettre_ne_declenche_aucune_proposition() {
        val m = listOf(connu("F4HRJ", 14, 5))
        assertTrue(Indicatifs.suggestions("F", m, maintenant).isEmpty())
        assertTrue(Indicatifs.suggestions("", m, maintenant).isEmpty())
    }

    /**
     * Trois propositions au maximum. Au-delà, la ligne demande une lecture au
     * lieu d'un coup d'œil, et l'on a reperdu les secondes qu'on venait gagner.
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
     * Le satellite en cours est le meilleur indice court dont on dispose
     * pendant un passage : à égalité par ailleurs, celui qu'on a déjà entendu
     * sur RS-44 passe devant.
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

    // --------------------------------------------------------------- clavier

    /**
     * Mettre en valeur, jamais retirer : `suitesConnues` rend les lettres qui
     * prolongent quelque chose, et le clavier s'en sert pour grossir des
     * touches — toutes les autres restent frappables.
     */
    @Test
    fun les_suites_connues_ne_sont_qu_une_mise_en_valeur() {
        val m = listOf(connu("F4HRJ", 1, 1), connu("F4HKA", 1, 1), connu("F4IOZ", 1, 1))
        assertEquals(setOf('R', 'K'), Indicatifs.suitesConnues("F4H", m))
        assertEquals(setOf('H', 'I'), Indicatifs.suitesConnues("F4", m))
        // Rien de connu ne prolonge : l'ensemble est vide, et le clavier reste
        // entier — c'est le cas du DX rare jamais contacté.
        assertTrue(Indicatifs.suitesConnues("ZZ9", m).isEmpty())
    }

    // -------------------------------------------------------------- locators

    /**
     * Le cas qui compte le plus. `/P` dit précisément que le correspondant
     * s'est déplacé : hériter du carré reviendrait à inscrire avec assurance le
     * seul carré dont on sait qu'il est faux — et un champ pré-rempli est un
     * champ accepté sans être lu.
     */
    @Test
    fun un_suffixe_portable_n_herite_jamais_du_carre() {
        // Même garde, portée par la clé : F4HRJ/P ne trouve pas l'entrée
        // F4HRJ, donc n'hérite pas de JN18.
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
        // Cet essai consacrait « le plus récent gagne » — la règle qui a
        // laissé trois contacts fautifs évincer dix-sept bons chez F5RRO.
        // C'est désormais le plus fréquent : JN07 et ses quatre contacts.
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
        // Troisième forme de cet essai. La première consacrait la fuite du
        // carré /P vers la base ; la deuxième jetait le carré du /P, qui
        // devenait invisible ; celle-ci sépare : deux entrées, chacune ses
        // carrés. F5RRO propose JN18, F5RRO/P propose JN33 — retour d'Olivier
        // sur la 18.73.
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
     * Le nom du correspondant, retenu à l'import.
     *
     * 3668 des 3763 contacts du carnet d'Olivier en portent un. Il ne sert à
     * aucun calcul et à tout à l'usage : reconnaître « Olivier » d'un coup
     * d'œil vaut mieux que relire cinq caractères.
     */
    @Test
    fun le_nom_du_correspondant_est_retenu() {
        val m = Indicatifs.memoire(listOf(
            Indicatifs.Contact("F5RRO", "JN33AF", maintenant, "FO-29", "Olivier Commeau")))
        assertEquals("Olivier Commeau", m[0].nom)
    }

    /**
     * Un carnet ne porte pas de nom à chaque ligne, et le contact le plus
     * récent est parfois justement celui qui n'en a pas : on garde le premier
     * nom vu, quelle que soit sa date.
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

/** Le rattrapage à la bande, sur les deux sources d'enregistrement. */
class RattrapageTest {

    private val debut = 1_800_000_000_000L

    @Test
    fun un_contact_se_situe_dans_la_bande() {
        assertEquals(90_000L, Rattrapage.position(debut + 90_000L, debut))
        assertTrue(Rattrapage.dansLaBande(debut + 90_000L, debut, 720_000L))
        assertFalse(Rattrapage.dansLaBande(debut + 900_000L, debut, 720_000L))
    }

    /**
     * Un enregistrement lancé en retard donne une position négative. Il faut
     * qu'elle se voie : la ramener à zéro en silence ferait écouter le début de
     * la bande en croyant y trouver un contact qui n'y est pas.
     */
    @Test
    fun un_contact_anterieur_a_la_bande_rend_une_position_negative() {
        assertTrue(Rattrapage.position(debut - 5_000L, debut) < 0)
        assertFalse(Rattrapage.dansLaBande(debut - 5_000L, debut, 720_000L))
    }

    /**
     * Le point d'alignement unique, qui rattrape un enregistreur extérieur.
     * Un passage dure douze minutes et la dérive d'un dictaphone y est très
     * inférieure à la seconde : un seul repère cale tout le fichier.
     */
    @Test
    fun un_seul_point_d_alignement_cale_tout_le_fichier() {
        val contacts = listOf(debut + 60_000L, debut + 180_000L, debut + 400_000L)
        // L'opérateur reconnaît le deuxième contact à 2 min 05 dans le fichier.
        val deduit = Rattrapage.debutDeduit(contacts[1], 125_000L)
        assertEquals(debut + 55_000L, deduit)
        // Les deux autres tombent en place tout seuls.
        assertEquals(5_000L, Rattrapage.position(contacts[0], deduit))
        assertEquals(345_000L, Rattrapage.position(contacts[2], deduit))
    }

    @Test
    fun la_fenetre_d_ecoute_commence_avant_le_tampon() {
        // On parle avant d'appuyer : la marge amont doit être la plus large.
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
     * Un alignement absurde se refuse. Sans ce garde-fou, désigner le mauvais
     * fichier ferait écouter du silence en croyant avoir raté son repère.
     */
    @Test
    fun un_alignement_absurde_est_refuse() {
        val contacts = listOf(debut, debut + 600_000L)
        assertTrue(Rattrapage.alignementVraisemblable(debut - 30_000L, contacts))
        assertFalse(Rattrapage.alignementVraisemblable(debut + 3 * 86_400_000L, contacts))
        assertFalse(Rattrapage.alignementVraisemblable(debut, emptyList()))
    }

    /**
     * Une poignée de contacts fautifs ne doit pas évincer un carré établi.
     * Cas réel F5RRO : 17 contacts en JN18FR, 3 écrits par erreur en JN33AF
     * plus récemment.
     */
    @Test
    fun le_carre_le_plus_frequent_l_emporte_sur_le_plus_recent() {
        // Constantes locales : cette classe-ci n'a pas celles de la classe
        // voisine, et un essai ne doit rien emprunter à son voisinage.
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

    /** À égalité de comptes, le plus récent tranche. */
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

/** L'import ADIF, qui n'alimente que la mémoire prédictive. */
class AdifImportTest {

    private fun enr(vararg champs: Pair<String, String>): String =
        champs.joinToString("") { (k, v) -> "<$k:${v.length}>$v" } + "<EOR>\n"

    @Test
    fun un_champ_se_lit_par_sa_longueur_declaree() {
        val f = AdifImport.champs("<CALL:5>F4IOZ<GRIDSQUARE:6>JN18FS<EOR>")
        assertEquals("F4IOZ", f["CALL"])
        assertEquals("JN18FS", f["GRIDSQUARE"])
    }

    /** La longueur fait foi, même quand la valeur contient un espace. */
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
     * Tolérance délibérée : un ADIF de carnet tiers comporte toujours quelque
     * chose d'inattendu, et un import qui échoue en entier sur un
     * enregistrement bancal ne sert personne.
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
        // Quatre chiffres : les secondes valent zéro.
        assertEquals(AdifImport.instant("20260805", "1437"),
            AdifImport.instant("20260805", "143700"))
        assertEquals(null, AdifImport.instant("2026080", "143722"))
        assertEquals(null, AdifImport.instant("20260805", "1"))
    }

    /** Le champ NAME de l'ADIF alimente le nom affiché. */
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
     * On ne garde que le satellite. Les correspondants d'un contest VHF ne
     * feraient que pousser vers le bas ceux qu'on va vraiment entendre pendant
     * un passage.
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

    /** Bout à bout : un export Wavelog devient une mémoire interrogeable. */
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
     * F5RRO/P est ailleurs que F5RRO : le carré appris pendant une sortie
     * portable ne doit jamais être proposé pour la station de base. C'est le
     * défaut relevé par Olivier — taper F5RRO proposait le carré de F5RRO/P.
     */
    @Test
    fun le_carre_d_une_sortie_portable_n_alimente_pas_la_station_de_base() {
        val m = Indicatifs.memoire(listOf(
            Indicatifs.Contact("F5RRO", "IN95", 1_000L, "RS-44"),
            Indicatifs.Contact("F5RRO/P", "JN17", 2_000L, "RS-44"),
        ))
        val base = m.first { it.indicatif == "F5RRO" }
        // Un seul carré pour la base : IN95 reste IN95. (J'avais remplacé
        // cette valeur par erreur en corrigeant l'essai voisin.)
        assertEquals("IN95", base.locatorPrincipal)
        // Et la sortie portable propose le sien, au lieu d'être muette.
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
     * Le bilan compte les **indicatifs distincts**, pas les contacts.
     *
     * C'est le seul chiffre qui décrive ce que le clavier a gagné : trente-huit
     * QSO avec le même correspondant n'ajoutent qu'une entrée à sa mémoire, et
     * annoncer les contacts laisserait croire à un enrichissement qui n'a pas
     * eu lieu.
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

    /** Ce qui est perdu, et pourquoi : sans indicatif ou sans date. */
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
