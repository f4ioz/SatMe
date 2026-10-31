package fr.f4ioz.satcombo.demo

import fr.f4ioz.satcombo.plateforme.StockageMemoire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModulesStationTest {

    private val tous = ModulesStation.MODULES.map { it.id }.toSet()

    @Test fun une_station_qui_avait_coche_des_fonctions_non_sat_les_garde() {
        val st = StockageMemoire()
        FonctionsNonSat.migre(st) { it == "ais" }
        assertEquals(setOf("sondes", "avions") + ModulesStation.SUR_DEMANDE, ModulesStation.migre(st))
        assertTrue(ModulesStation.actif(st, "ais"))
        assertTrue(ModulesStation.actif(st, "qo100"))
        assertFalse(ModulesStation.actif(st, "avions"))
        // Decided once: the old key changed afterwards does not count.
        st.edit().putStringSet(FonctionsNonSat.CLE, setOf("avions", "ais", "sondes")).apply()
        assertEquals(setOf("sondes", "avions") + ModulesStation.SUR_DEMANDE, ModulesStation.migre(st))
    }

    @Test fun avant_tout_choix_seules_les_fonctions_non_sat_decochees_sont_coupees() {
        val st = StockageMemoire()
        assertEquals(setOf("sondes", "avions", "ais") + ModulesStation.SUR_DEMANDE, ModulesStation.coupes(st))
        st.edit().putStringSet(FonctionsNonSat.CLE, setOf("sondes", "avions", "ais")).apply()
        assertEquals(ModulesStation.SUR_DEMANDE, ModulesStation.coupes(st))
    }

    @Test fun poser_garde_la_cle_des_fonctions_non_sat_au_pas() {
        val st = StockageMemoire()
        ModulesStation.pose(st, setOf("qo100", "avions", "inconnu"))
        assertEquals(setOf("qo100", "avions"), ModulesStation.coupes(st))
        assertEquals(setOf("sondes", "ais"), FonctionsNonSat.choisis(st))
        assertFalse(FonctionsNonSat.actif(st, "avions"))
    }

    @Test fun les_profils() {
        assertEquals(ModulesStation.SUR_DEMANDE, ModulesStation.profil("tout"))
        assertEquals(emptySet<String>(), ModulesStation.profil("tout", emptySet()))
        assertNull(ModulesStation.profil("rien"))
        val sstv = ModulesStation.profil("sstv")!!
        assertFalse("sstv" in sstv); assertFalse("sdr" in sstv)
        assertTrue("qo100" in sstv); assertTrue("datv" in sstv); assertTrue("avions" in sstv)
        val qo = ModulesStation.profil("qo100")!!
        assertFalse("datv" in qo); assertTrue("sstv" in qo)
        // Every profile names known modules only, and is found back from what it leaves off.
        ModulesStation.PROFILS.forEach { p ->
            assertTrue(p.id, tous.containsAll(p.modules))
            assertEquals(p.id, ModulesStation.profilDe(ModulesStation.profil(p.id)!!))
            // A profile never switches « Mon site web » on, nor off when the owner has it on.
            assertFalse(p.id, ModulesStation.SITE in p.modules)
            assertTrue(p.id, ModulesStation.SITE in ModulesStation.profil(p.id)!!)
            assertFalse(p.id, ModulesStation.SITE in ModulesStation.profil(p.id, emptySet())!!)
            assertEquals(p.id, ModulesStation.profilDe(ModulesStation.profil(p.id, emptySet())!!))
        }
        assertNull(ModulesStation.profilDe(setOf("qo100")))
    }

    @Test fun mon_site_web_est_coupe_par_defaut() {
        // A new install: off, whatever else is chosen.
        val neuf = StockageMemoire()
        assertFalse(ModulesStation.actif(neuf, ModulesStation.SITE))
        assertTrue(ModulesStation.SITE in ModulesStation.migre(neuf))
        assertFalse(ModulesStation.actif(neuf, ModulesStation.SITE))
        assertTrue(ModulesStation.module(ModulesStation.SITE)!!.surDemande)
        // Switched on by the owner: kept (decided), a later start does not undo it.
        ModulesStation.pose(neuf, ModulesStation.bascule(ModulesStation.coupes(neuf), ModulesStation.SITE, true))
        assertTrue(ModulesStation.actif(neuf, ModulesStation.SITE))
        ModulesStation.migre(neuf) { false }
        assertTrue(ModulesStation.actif(neuf, ModulesStation.SITE))
    }

    @Test fun une_station_d_avant_le_module_site_le_garde_seulement_s_il_etait_regle() {
        // Modules already chosen before « Mon site web » existed: the site set up → on, the rest unchanged.
        val regle = StockageMemoire()
        regle.edit().putStringSet(ModulesStation.CLE, setOf("datv")).apply()
        assertFalse(ModulesStation.actif(regle, ModulesStation.SITE))
        // The other modules switched on by the owner only (assistant, home automation) stay off.
        val autres = ModulesStation.SUR_DEMANDE - ModulesStation.SITE
        assertEquals(setOf("datv") + autres, ModulesStation.migre(regle) { it == ModulesStation.SITE })
        assertTrue(ModulesStation.actif(regle, ModulesStation.SITE))
        // Decided once: the owner's later choice stays.
        ModulesStation.pose(regle, setOf("datv", ModulesStation.SITE))
        assertEquals(setOf("datv", ModulesStation.SITE), ModulesStation.migre(regle) { true })
        // Not set up: off.
        val non = StockageMemoire()
        non.edit().putStringSet(ModulesStation.CLE, emptySet()).apply()
        assertEquals(ModulesStation.SUR_DEMANDE, ModulesStation.migre(non) { false })
        assertFalse(ModulesStation.actif(non, ModulesStation.SITE))
    }

    @Test fun bascule() {
        assertEquals(setOf("qo100"), ModulesStation.bascule(emptySet(), "qo100", false))
        assertEquals(emptySet<String>(), ModulesStation.bascule(setOf("qo100"), "qo100", true))
        assertEquals(setOf("qo100"), ModulesStation.bascule(setOf("qo100"), "station", false))
    }

    @Test fun les_onglets_des_modules_coupes_quittent_le_menu() {
        val t = listOf("station", "qo100", "datv", "sstv", "planche", "carnet", "avions", "reglages")
        assertEquals(t, ModulesStation.onglets(t, emptySet()))
        assertEquals(listOf("station", "datv", "carnet", "avions", "reglages"), ModulesStation.onglets(t, setOf("qo100", "sstv")))
        // The core is never anyone's.
        val coeur = PagePc.TOUS.filter { ModulesStation.proprietaire(it) == null }
        assertEquals(listOf("station", "carnet", "agenda", "carres", "frise", "sked", "carte", "son", "images", "journal", "reglages"), coeur)
        // « Images » leaves with both SSTV and Télémesure, stays with either.
        assertEquals(coeur - "images", ModulesStation.onglets(PagePc.TOUS, tous))
        assertTrue("images" in ModulesStation.onglets(PagePc.TOUS, setOf("sstv")))
        assertTrue("images" in ModulesStation.onglets(PagePc.TOUS, setOf("tlm")))
        assertFalse("images" in ModulesStation.onglets(PagePc.TOUS, setOf("sstv", "tlm")))
        // Every module's tab exists.
        ModulesStation.MODULES.flatMap { it.onglets }.forEach { assertTrue(it, it in PagePc.TOUS) }
    }

    @Test fun les_masques_des_modules_coupes() {
        assertEquals(listOf("#sb-datv"), ModulesStation.masques(setOf("datv")))
        assertTrue(ModulesStation.masques(setOf("sdr")).contains("#sb-sdr"))
        assertEquals(emptyList<String>(), ModulesStation.masques(emptySet()))
    }
}
