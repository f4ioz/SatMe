/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.Convertisseur
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le convertisseur : un OL, un sens, une plage.
 *
 * Ce qu'on éprouve ici tient en trois choses. Que les chiffres tombent juste
 * sur les montages réels — un ham qui lit 739,750 sait tout de suite que
 * c'est bon, et 739,760 lui sauterait aux yeux. Que l'aller-retour soit exact,
 * parce que la relecture de la molette passe par le sens inverse et qu'un
 * hertz d'écart y serait pris pour un geste de l'opérateur. Et surtout qu'un
 * convertisseur resté coché ne touche à rien hors de sa bande : c'est la seule
 * garantie qui empêche de casser tous les autres satellites en réglant
 * celui-là.
 */
class ConvertisseurTest {

    private val lnb9750 = Convertisseur(
        actif = true, olHz = 9_750_000_000L,
        basHz = 10_400_000_000L, hautHz = 10_800_000_000L)

    private val lnb10000 = lnb9750.copy(olHz = 10_000_000_000L)

    private val tvtr432 = Convertisseur(
        actif = true, olHz = 1_968_000_000L,
        basHz = 2_390_000_000L, hautHz = 2_450_000_000L)

    private val tvtr144 = tvtr432.copy(olHz = 2_256_000_000L)

    // --- Les chiffres du terrain ----------------------------------------

    @Test
    fun le_lnb_9750_ramene_la_balise_mediane_a_739_750() {
        assertEquals(739_750_000L, lnb9750.versPoste(10_489_750_000L))
    }

    @Test
    fun le_lnb_10000_ramene_la_balise_mediane_a_489_750() {
        assertEquals(489_750_000L, lnb10000.versPoste(10_489_750_000L))
    }

    @Test
    fun le_transverter_432_emet_le_bas_du_transpondeur_etroit() {
        // Bord bas de la montée NB : 2400,005 MHz, piloté en 432,005.
        assertEquals(432_005_000L, tvtr432.versPoste(2_400_005_000L))
    }

    @Test
    fun le_transverter_144_emet_le_bas_du_transpondeur_etroit() {
        assertEquals(144_005_000L, tvtr144.versPoste(2_400_005_000L))
    }

    @Test
    fun les_bords_du_transpondeur_etroit_gardent_leur_ecart() {
        // 492 kHz de large en haut comme en bas : une conversion soustractive
        // décale tout le monde du même nombre de hertz, elle ne comprime rien.
        val bas = lnb9750.versPoste(10_489_505_000L)
        val haut = lnb9750.versPoste(10_489_997_000L)
        assertEquals(492_000L, haut - bas)
    }

    // --- L'aller-retour --------------------------------------------------

    @Test
    fun ce_qui_monte_redescend_a_l_identique() {
        val sat = 10_489_612_345L
        assertEquals(sat, lnb9750.versSatellite(lnb9750.versPoste(sat)))
    }

    @Test
    fun l_aller_retour_tient_aussi_a_l_emission() {
        val sat = 2_400_312_500L
        assertEquals(sat, tvtr432.versSatellite(tvtr432.versPoste(sat)))
    }

    // --- L'injection haute ----------------------------------------------

    @Test
    fun un_inverseur_retourne_la_bande() {
        val inv = Convertisseur(actif = true, olHz = 11_000_000_000L, inverseur = true,
            basHz = 10_400_000_000L, hautHz = 10_800_000_000L)
        // Plus haut dans le ciel, plus bas sur le poste.
        val bas = inv.versPoste(10_489_500_000L)
        val haut = inv.versPoste(10_489_900_000L)
        assertTrue("le spectre ne s'est pas retourné", haut < bas)
        assertEquals(510_500_000L, bas)
    }

    @Test
    fun l_inverseur_est_sa_propre_reciproque() {
        val inv = Convertisseur(actif = true, olHz = 11_000_000_000L, inverseur = true,
            basHz = 10_400_000_000L, hautHz = 10_800_000_000L)
        val sat = 10_489_750_000L
        assertEquals(sat, inv.versSatellite(inv.versPoste(sat)))
    }

    // --- La plage, c'est-à-dire la sûreté --------------------------------

    @Test
    fun hors_de_sa_bande_un_convertisseur_ne_touche_a_rien() {
        // Le LNB reste coché, et l'on travaille l'ISS. Si la soustraction
        // s'appliquait, on écrirait une fréquence négative dans le poste.
        assertEquals(145_800_000L, lnb9750.versPoste(145_800_000L))
        assertEquals(435_300_000L, lnb9750.versPoste(435_300_000L))
        assertFalse(lnb9750.couvre(145_800_000L))
    }

    @Test
    fun le_lnb_et_le_transverter_cohabitent_sans_se_marcher_dessus() {
        // Les deux restent actifs en permanence ; chacun ne voit que sa bande.
        assertTrue(lnb9750.couvre(10_489_750_000L))
        assertFalse(lnb9750.couvre(2_400_150_000L))
        assertTrue(tvtr432.couvre(2_400_150_000L))
        assertFalse(tvtr432.couvre(10_489_750_000L))
    }

    @Test
    fun la_relecture_hors_plage_ne_fabrique_pas_un_saut_de_dix_gigahertz() {
        // Le poste affiche 435,300 : c'est un satellite en 435,300, pas en
        // 10 185. Sans cette garde, chaque relecture passerait pour un geste.
        assertEquals(435_300_000L, lnb9750.versSatellite(435_300_000L))
    }

    @Test
    fun decoche_il_est_transparent() {
        val off = lnb9750.copy(actif = false)
        assertEquals(10_489_750_000L, off.versPoste(10_489_750_000L))
        assertEquals(739_750_000L, off.versSatellite(739_750_000L))
        assertFalse(off.configure)
    }

    @Test
    fun un_oscillateur_a_zero_vaut_pas_de_convertisseur() {
        val vide = Convertisseur(actif = true, olHz = 0L)
        assertFalse(vide.configure)
        assertEquals(10_489_750_000L, vide.versPoste(10_489_750_000L))
    }

    @Test
    fun sans_bornes_le_convertisseur_s_applique_partout_ou_le_resultat_tient() {
        val libre = Convertisseur(actif = true, olHz = 9_750_000_000L)
        assertEquals(739_750_000L, libre.versPoste(10_489_750_000L))
        // …mais jamais jusqu'à sortir une fréquence négative.
        assertEquals(145_800_000L, libre.versPoste(145_800_000L))
    }

    // --- Les préréglages -------------------------------------------------

    @Test
    fun les_preregleges_donnent_bien_les_fi_annoncees() {
        val attendu = mapOf(
            "lnb9750" to 739_750_000L,
            "lnb10000" to 489_750_000L,
            "lnb10057" to 432_250_000L,
            // L'OL mesuré pose la balise médiane à 144,777 et non 145,750 :
            // ce sont les 27 kHz du TCXO du LNB, plus le décalage du nominal.
            "down145" to 144_777_000L)
        Convertisseur.PRESETS.filter { it.descente }.forEach { p ->
            assertEquals("préréglage ${p.cle}",
                attendu[p.cle], p.vers().versPoste(Convertisseur.BALISE_MEDIANE_HZ))
        }
    }

    @Test
    fun les_preregleges_de_montee_couvrent_le_transpondeur_etroit() {
        Convertisseur.PRESETS.filter { !it.descente }.forEach { p ->
            val c = p.vers()
            assertTrue("préréglage ${p.cle} ne couvre pas la montée NB",
                c.couvre(2_400_005_000L) && c.couvre(2_400_490_000L))
        }
    }

    // --- Le montage d'Olivier : descente en 145, montée en 432 -----------

    /**
     * Le préréglage `down145` doit poser *tout* le transpondeur étroit dans
     * les 2 m, pas seulement la balise. Si un seul bord sortait de la bande,
     * la moitié du transpondeur serait injoignable et cela ne se verrait
     * qu'en essayant d'appeler quelqu'un.
     */
    @Test
    fun le_downconverter_145_range_tout_le_transpondeur_etroit_dans_les_2_m() {
        val c = Convertisseur.PRESETS.first { it.cle == "down145" }.vers()
        val bas = c.versPoste(10_489_505_000L)
        val haut = c.versPoste(10_489_997_000L)
        assertEquals(144_532_000L, bas)
        assertEquals(145_024_000L, haut)
        // La bande 2 m de l'IC-9700 : 144 – 148 MHz, avec de la marge.
        assertTrue("le bas sort des 2 m", bas in 144_000_000L..148_000_000L)
        assertTrue("le haut sort des 2 m", haut in 144_000_000L..148_000_000L)
    }

    @Test
    fun le_downconverter_145_pose_les_trois_balises_ou_on_les_attend() {
        val c = Convertisseur.PRESETS.first { it.cle == "down145" }.vers()
        // Ce sont les fréquences réellement observées au FT-817, et non des
        // valeurs rondes : l'oscillateur du LNB porte ses 27 kHz d'écart.
        assertEquals(144_527_000L, c.versPoste(10_489_500_000L))
        assertEquals(144_777_000L, c.versPoste(10_489_750_000L))
        assertEquals(145_027_000L, c.versPoste(10_490_000_000L))
    }

    /**
     * La paire croisée : le poste reçoit en 145 et émet en 432. C'est la
     * combinaison qu'Olivier monte, et les deux convertisseurs doivent tenir
     * ensemble sans se marcher dessus.
     */
    @Test
    fun la_paire_145_rx_432_tx_tient_avec_les_deux_convertisseurs() {
        val descente = Convertisseur.PRESETS.first { it.cle == "down145" }.vers()
        val montee = Convertisseur.PRESETS.first { it.cle == "tvtr432" }.vers()
        assertEquals(144_777_000L, descente.versPoste(10_489_750_000L))
        assertEquals(432_250_000L, montee.versPoste(2_400_250_000L))
        // Chacun ignore la bande de l'autre.
        assertFalse(descente.couvre(2_400_250_000L))
        assertFalse(montee.couvre(10_489_750_000L))
        // Et ni l'un ni l'autre ne touche à la FI de son voisin.
        assertEquals(144_777_000L, montee.versPoste(144_777_000L))
        assertEquals(432_250_000L, descente.versPoste(432_250_000L))
    }

    @Test
    fun le_downconverter_145_fait_l_aller_retour_sans_perdre_un_hertz() {
        val c = Convertisseur.PRESETS.first { it.cle == "down145" }.vers()
        listOf(10_489_505_000L, 10_489_750_000L, 10_489_997_000L).forEach { sat ->
            assertEquals(sat, c.versSatellite(c.versPoste(sat)))
        }
    }

    @Test
    fun aucun_prereglage_ne_deborde_sur_la_bande_d_un_autre() {
        Convertisseur.PRESETS.forEach { p ->
            val c = p.vers()
            if (p.descente) assertFalse("${p.cle} déborde sur le 13 cm",
                c.couvre(2_400_150_000L))
            else assertFalse("${p.cle} déborde sur le 10 GHz",
                c.couvre(10_489_750_000L))
        }
    }

    // ------------------------------- la chaîne mesurée de F4IOZ

    /**
     * Le préréglage `down145` porte une valeur **mesurée**, pas théorique.
     *
     * Relevé du 3 septembre 2026 : un même QSO lu à 10 489,805 59 MHz sur le
     * WebSDR IS0GRB — sur GPSDO, donc référence absolue — et à 144,832 65 MHz
     * sur le FT-817 de la station. L'oscillateur local de la chaîne vaut donc
     * 10 344,972 94 MHz, et non les 10 344,000 du nominal.
     *
     * L'écart n'est pas un défaut : c'est le TCXO du LNB, 2,6 ppm à 9 750 MHz,
     * conforme à sa spécification. Il est constant d'un allumage à l'autre.
     */
    @Test
    fun le_preset_down145_porte_la_valeur_mesuree() {
        val p = Convertisseur.PRESETS.first { it.cle == "down145" }
        assertEquals(10_344_973_000L, p.olHz)
        assertTrue(p.descente)
    }

    @Test
    fun le_qso_mesure_retombe_sur_l_affichage_du_poste() {
        val c = Convertisseur.PRESETS.first { it.cle == "down145" }.vers()
        // Le relevé du 817 était 144,832 65 ; l'OL arrondi au kilohertz laisse
        // soixante hertz, très en deçà de ce qu'on distingue à l'oreille en SSB.
        val fi = c.versPoste(10_489_805_590L)
        assertTrue("écart de ${144_832_650L - fi} Hz",
            kotlin.math.abs(144_832_650L - fi) < 200L)
    }

    @Test
    fun les_trois_balises_tombent_dans_les_deux_metres() {
        val c = Convertisseur.PRESETS.first { it.cle == "down145" }.vers()
        assertEquals(144_527_000L, c.versPoste(Convertisseur.BALISE_MEDIANE_HZ - 250_000L))
        assertEquals(144_777_000L, c.versPoste(Convertisseur.BALISE_MEDIANE_HZ))
        assertEquals(145_027_000L, c.versPoste(Convertisseur.BALISE_HAUTE_HZ))
    }

    /**
     * L'aller-retour doit être exact : c'est lui qui garantit qu'un contact
     * journalisé porte la fréquence du ciel et non celle de la FI.
     */
    @Test
    fun l_aller_retour_de_la_chaine_mesuree_est_exact() {
        val c = Convertisseur.PRESETS.first { it.cle == "down145" }.vers()
        listOf(10_489_500_000L, 10_489_750_000L, 10_489_805_590L, 10_490_000_000L)
            .forEach { assertEquals(it, c.versSatellite(c.versPoste(it))) }
    }

    /** La balise haute est un second point d'étalonnage, à 500 kHz du premier. */
    @Test
    fun les_deux_balises_sont_distantes_de_cinq_cents_kilohertz() {
        assertEquals(500_000L,
            Convertisseur.BALISE_HAUTE_HZ - (Convertisseur.BALISE_MEDIANE_HZ - 250_000L))
    }

    /**
     * Le piège qui a fait apparaître une fréquence QO-100 sur un LEO.
     *
     * Les bornes d'un convertisseur portent sur le **résultat**, côté ciel. En
     * remontant du poste vers le ciel, une fréquence de LEO peut donc tomber
     * dans la fenêtre par accident : 145,9 MHz plus l'oscillateur du LNB fait
     * 10 490,9, qui est bien dans le Ku. La garde laissait passer, et l'écran
     * affichait une descente QO-100 sur une orbite basse.
     *
     * Ces essais fixent le fait, pour qu'on se souvienne que la garde de
     * [Convertisseur] ne suffit pas seule : c'est au satellite en cours de
     * décider si le convertisseur est dans la chaîne.
     */
    @Test
    fun une_frequence_de_leo_tombe_par_accident_dans_la_fenetre_ku() {
        val lnb = Convertisseur(actif = true, olHz = 10_344_973_000L,
            basHz = 10_400_000_000L, hautHz = 10_800_000_000L)
        // 145,9 MHz remonté donne 10 490,9 : dans les bornes, donc converti.
        val remonte = lnb.versSatellite(145_900_000L)
        assertEquals(10_490_873_000L, remonte)
        assertTrue("la garde de bande ne peut pas voir l'erreur", lnb.couvre(remonte))
    }

    /** La descente du satellite, elle, ne ment pas : 435 n'est pas dans le Ku. */
    @Test
    fun la_descente_du_satellite_tranche_sans_ambiguite() {
        val lnb = Convertisseur(actif = true, olHz = 10_344_973_000L,
            basHz = 10_400_000_000L, hautHz = 10_800_000_000L)
        assertTrue(lnb.couvre(10_489_750_000L))
        assertTrue(!lnb.couvre(435_350_000L))
        assertTrue(!lnb.couvre(145_950_000L))
    }
}
