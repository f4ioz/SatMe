package fr.f4ioz.satcombo.demo

import fr.f4ioz.satcombo.plateforme.StockageMemoire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FonctionsNonSatTest {

    @Test fun nouvelle_station_tout_decoche() {
        val st = StockageMemoire()
        assertNull(FonctionsNonSat.choisis(st))
        assertEquals(emptySet<String>(), FonctionsNonSat.migre(st) { false })
        assertEquals(emptySet<String>(), FonctionsNonSat.choisis(st))
        assertFalse(FonctionsNonSat.actif(st, "avions"))
        assertTrue(FonctionsNonSat.actif(st, "station"))
    }

    @Test fun ce_qui_a_servi_reste_coche() {
        val st = StockageMemoire()
        assertEquals(setOf("avions", "sondes"), FonctionsNonSat.migre(st) { it != "ais" })
    }

    @Test fun decide_une_seule_fois() {
        val st = StockageMemoire()
        FonctionsNonSat.migre(st) { false }
        // Used later: the owner's choice (none) stays.
        assertEquals(emptySet<String>(), FonctionsNonSat.migre(st) { true })
        FonctionsNonSat.pose(st, "ais", true)
        assertEquals(setOf("ais"), FonctionsNonSat.migre(st) { false })
    }

    @Test fun une_erreur_ne_coche_pas() {
        val st = StockageMemoire()
        assertEquals(setOf("ais"), FonctionsNonSat.migre(st) { if (it == "avions") error("x") else it == "ais" })
    }

    @Test fun cocher_decocher() {
        val st = StockageMemoire()
        FonctionsNonSat.pose(st, "sondes", true)
        FonctionsNonSat.pose(st, "avions", true)
        FonctionsNonSat.pose(st, "sondes", false)
        FonctionsNonSat.pose(st, "station", false)
        assertEquals(setOf("avions"), FonctionsNonSat.choisis(st))
        assertEquals(setOf("avions"), FonctionsNonSat.pose(st, "inconnu", true))
    }

    @Test fun onglets_filtres() {
        val tous = listOf("station", "sondes", "carte", "avions", "ais", "reglages")
        assertEquals(listOf("station", "carte", "reglages"), FonctionsNonSat.onglets(tous, emptySet()))
        assertEquals(listOf("station", "carte", "ais", "reglages"), FonctionsNonSat.onglets(tous, setOf("ais")))
    }

    @Test fun reglage_present_quel_que_soit_son_type() {
        val st = StockageMemoire()
        st.edit().putInt("i", 0).putBoolean("b", false).putString("s", "").putLong("l", 3).putFloat("f", 1f).apply()
        listOf("i", "b", "s", "l", "f").forEach { assertTrue(it, FonctionsNonSat.present(st, it)) }
        assertFalse(FonctionsNonSat.present(st, "absent"))
    }
}
