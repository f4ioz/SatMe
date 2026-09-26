/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.diag.ModeEchec
import fr.f4ioz.satcombo.diag.Plantage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le mode échec ne se vérifie pas sur le terrain : quand on en a besoin, on
 * n'a précisément plus rien pour observer. Il se vérifie donc ici, là où l'on
 * peut fabriquer des pannes qui n'arrivent pas sur commande — une mémoire
 * épuisée, une classe absente, une chaîne de causes qui se mord la queue.
 */
class ModeEchecTest {

    private fun horlogeFigee(vararg valeurs: Long): () -> Long {
        var i = 0
        return { valeurs[minOf(i++, valeurs.size - 1)] }
    }

    @Test
    fun une_epreuve_qui_reussit_raconte_ce_qu_elle_a_vu() {
        val r = ModeEchec.execute(
            ModeEchec.Etape("lecture") { "3 262 504 octets" },
            horlogeFigee(1000, 1042))
        assertTrue(r.ok)
        assertEquals("3 262 504 octets", r.detail)
        assertEquals(42L, r.ms)
    }

    /**
     * Le point le plus important du fichier. Les pannes que le mode échec
     * cherche ne sont pas des exceptions ordinaires : mémoire épuisée sur un
     * appareil modeste, classe absente sur un téléphone sans services Google,
     * bibliothèque native refusée. Attraper `Exception` au lieu de `Throwable`
     * les laisserait toutes passer — et le diagnostic mourrait de ce qu'il est
     * venu diagnostiquer.
     */
    @Test
    fun on_attrape_les_erreurs_et_pas_seulement_les_exceptions() {
        val memoire = ModeEchec.execute(ModeEchec.Etape("mémoire") { throw OutOfMemoryError("bitmap") })
        assertFalse(memoire.ok)
        assertTrue(memoire.detail.contains("OutOfMemoryError"))

        val classe = ModeEchec.execute(
            ModeEchec.Etape("google") { throw NoClassDefFoundError("com/google/android/gms/X") })
        assertFalse(classe.ok)
        assertTrue(classe.detail.contains("NoClassDefFoundError"))

        val native = ModeEchec.execute(
            ModeEchec.Etape("lame") { throw UnsatisfiedLinkError("libandroidlame.so") })
        assertFalse(native.ok)
        assertTrue(native.detail.contains("UnsatisfiedLinkError"))
    }

    @Test
    fun la_cause_racine_est_nommee_quand_elle_differe_de_la_surface() {
        val racine = IllegalStateException("WorkManager is not initialized properly")
        val surface = RuntimeException("échec du démarrage", racine)
        val d = ModeEchec.decrit(surface)
        assertTrue(d.contains("RuntimeException"))
        assertTrue(d.contains("cause"))
        assertTrue(d.contains("WorkManager is not initialized properly"))
    }

    /**
     * Une exception sans message se lisait « null », mot qui ne désigne rien et
     * qu'on prend pour un défaut du rapport plutôt que pour un fait.
     */
    @Test
    fun une_exception_sans_message_ne_produit_pas_le_mot_null() {
        val d = ModeEchec.decrit(NullPointerException())
        assertTrue(d.contains("(sans message)"))
        assertFalse(d.contains(": null"))
    }

    @Test
    fun une_chaine_de_causes_circulaire_ne_fait_pas_boucler_le_rapport() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        val d = ModeEchec.decrit(a)   // doit rendre la main
        assertTrue(d.isNotEmpty())
    }

    @Test
    fun le_bloc_compte_les_echecs_en_tete() {
        val r = listOf(
            ModeEchec.Resultat("un", true, "bon", 3),
            ModeEchec.Resultat("deux", false, "IllegalStateException: rien", 7),
            ModeEchec.Resultat("trois", true, "bon", 1))
        val bloc = ModeEchec.bloc(r)
        assertTrue(bloc.contains("3 épreuves, 1 en échec"))
        assertTrue(bloc.contains("ÉCHEC deux"))
        assertTrue(bloc.contains("(7 ms)"))
    }

    @Test
    fun un_diagnostic_non_lance_le_dit_plutot_que_de_rendre_du_vide() {
        assertEquals("Diagnostic non lancé.", ModeEchec.bloc(emptyList()))
    }

    /**
     * Les deux coupes vont dans des sens opposés, et c'est le sens qui compte :
     * une pile d'appels se nomme par sa tête, un journal système par sa queue.
     */
    @Test
    fun la_pile_se_coupe_par_la_fin_et_le_journal_par_le_debut() {
        val texte = (1..2000).joinToString("\n") { "ligne $it" }

        val pile = Plantage.tronque(texte, 500)
        assertTrue(pile.startsWith("ligne 1"))

        val journal = ModeEchec.tronqueParLeDebut(texte, 500)
        assertTrue(journal.trimEnd().endsWith("ligne 2000"))
        assertTrue(journal.startsWith("[…]"))
        assertTrue(journal.contains("écartés"))
    }

    @Test
    fun un_texte_court_n_est_pas_coupe() {
        assertEquals("bref", ModeEchec.tronqueParLeDebut("bref", 500))
    }

    /**
     * Le motif 7 est celui d'un processus qui n'a jamais réussi à se construire.
     * C'est le seul de la table qu'aucun garde-fou à nous ne peut avoir noté,
     * puisqu'aucune de nos lignes n'a tourné — et donc le seul que ce registre
     * soit seul à pouvoir dire.
     */
    @Test
    fun le_motif_sept_est_un_echec_d_initialisation() {
        assertTrue(ModeEchec.nomDeRaison(7).contains("INITIALISATION"))
        assertTrue(ModeEchec.nomDeRaison(4).contains("PLANTAGE"))
        assertTrue(ModeEchec.nomDeRaison(6).contains("ANR"))
        assertTrue(ModeEchec.nomDeRaison(3).contains("mémoire"))
        assertTrue(ModeEchec.nomDeRaison(99).contains("99"))
    }

    @Test
    fun un_registre_vide_le_dit_au_lieu_de_se_taire() {
        val bloc = ModeEchec.blocSorties(emptyList())
        assertTrue(bloc.contains("vide"))
        assertTrue(bloc.contains("Android 11"))
    }

    @Test
    fun le_registre_nomme_chaque_arret_avec_sa_date() {
        val bloc = ModeEchec.blocSorties(listOf(
            ModeEchec.Sortie("2026-08-04 09:12:33", 4, "crash", 100, 0),
            ModeEchec.Sortie("2026-08-04 09:11:02", 7, null, 100, 51_200)))
        assertTrue(bloc.contains("2026-08-04 09:12:33"))
        assertTrue(bloc.contains("PLANTAGE"))
        assertTrue(bloc.contains("INITIALISATION"))
        assertTrue(bloc.contains("50 Mo"))
    }

    @Test
    fun le_rapport_porte_ses_quatre_sections_dans_l_ordre() {
        val r = ModeEchec.rapport(
            entete = "Appareil : Xiaomi",
            plantage = "java.lang.IllegalStateException",
            sorties = "Registre des arrêts : vide",
            diagnostic = "Diagnostic non lancé.",
            journal = "08-04 09:12:33 E AndroidRuntime")
        val i1 = r.indexOf("Dernier plantage")
        val i2 = r.indexOf("Arrêts du processus")
        val i3 = r.indexOf("Diagnostic")
        val i4 = r.indexOf("Journal système")
        assertTrue(i1 in 1..i2)
        assertTrue(i2 < i3)
        assertTrue(i3 < i4)
        assertTrue(r.contains("Xiaomi"))
    }

    /**
     * L'absence de rapport est un résultat, pas un blanc. Une section vide se
     * lit « je n'ai pas regardé » ; « aucun rapport sur le disque » se lit
     * « j'ai regardé, il n'y a rien » — et écarte alors une hypothèse au lieu
     * d'en laisser flotter deux.
     */
    @Test
    fun l_absence_de_plantage_enregistre_est_dite_en_toutes_lettres() {
        val r = ModeEchec.rapport("appareil", null, "registre", "diag", null)
        assertTrue(r.contains("Aucun rapport de plantage"))
        assertFalse(r.contains("Journal système"))
    }

    /**
     * Le corps du courrier passe par un tuyau du noyau partagé avec tout le
     * système. Un rapport complet, journal compris, le dépasse : on vérifie que
     * la coupe existe et qu'elle se signale.
     */
    @Test
    fun le_corps_du_courrier_reste_transportable() {
        val enorme = "x".repeat(200_000)
        val corps = Plantage.corpsDuMail(Plantage.tronque(enorme, 60_000))
        assertTrue(corps.length < 61_000)
        assertTrue(corps.contains("rapport coupé"))
    }
}
