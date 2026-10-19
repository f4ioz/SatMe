/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat.icomreseau

import fr.f4ioz.satcombo.cat.CivController
import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * The session against a pretend radio on localhost: login, streams, CI-V
 * through the CAT driver, audio, losses, the TX guard, and above all the
 * logout — the radio holds one session, a client that leaves without
 * handing it back locks the owner out.
 */
class SessionIcomTest {

    private val u = CodageIdentifiants.hex("74496a33")!!
    private val m = CodageIdentifiants.hex("262222745336364d")!!
    private val radios = ArrayList<FausseRadioIcom>()

    private fun radio(occupe: Boolean = false, perdAudio: Int = -1) =
        FausseRadioIcom(u, m, occupe, perdAudio).also { radios += it }

    private fun config(r: FausseRadioIcom, mdp: ByteArray = m) =
        ConfigReseau("127.0.0.1", r.portControle, r.portCiv, r.portAudio, u, mdp, latenceMs = 80)

    @After fun fin() { radios.forEach { it.arrete() } }

    private fun attends(ms: Long = 3_000, ok: () -> Boolean): Boolean {
        val fin = System.currentTimeMillis() + ms
        while (!ok() && System.currentTimeMillis() < fin) Thread.sleep(10)
        return ok()
    }

    @Test fun connexion_puis_deconnexion_propre() {
        val r = radio()
        val s = SessionIcom(config(r))
        assertTrue(s.statut.value.message, s.ouvre())
        assertEquals(SessionIcom.Etat.CONNECTE, s.statut.value.etat)
        assertEquals("IC-9700", s.statut.value.radio)
        assertEquals(0xA2, s.statut.value.adresseCiv)
        assertTrue(r.connecte)
        assertTrue(r.civOuvert)
        // Receive only: no TX audio path asked; the codec and rate chosen.
        assertEquals(false, r.txActifDemande)
        assertEquals(0x04, r.codecDemande)
        assertEquals(16_000, r.tauxDemande)
        s.ferme()
        assertTrue("jeton rendu", attends { r.jetonRendu })
        assertTrue(attends { r.civFerme })
        assertTrue(attends { "contrôle" in r.deconnexions && "civ" in r.deconnexions && "audio" in r.deconnexions })
        assertEquals(SessionIcom.Etat.ARRET, s.statut.value.etat)
    }

    @Test fun mauvais_mot_de_passe_refuse_sans_rester_en_ligne() {
        val r = radio()
        val s = SessionIcom(config(r, CodageIdentifiants.hex("00112233")!!))
        assertFalse(s.ouvre())
        assertEquals(SessionIcom.Etat.REFUSE, s.statut.value.etat)
        assertTrue(s.statut.value.message.contains("refusé"))
        assertTrue(attends { "contrôle" in r.deconnexions })
        assertFalse(r.connecte)
    }

    @Test fun rien_ne_repond() {
        val r = radio()
        val c = config(r)
        r.arrete()
        val s = SessionIcom(c)
        assertFalse(s.ouvre())
        assertEquals(SessionIcom.Etat.PERDU, s.statut.value.etat)
        assertTrue(s.statut.value.message, s.statut.value.message.contains("rien ne répond"))
    }

    @Test fun poste_deja_pris() {
        val r = radio(occupe = true)
        val s = SessionIcom(config(r))
        assertFalse(s.ouvre())
        assertTrue(s.statut.value.message, s.statut.value.message.contains("autre session"))
        // The token handed back all the same.
        assertTrue(attends { r.jetonRendu })
    }

    @Test fun le_cat_lit_et_ecrit_par_le_reseau_mais_n_emet_pas() = runBlocking {
        val r = radio()
        val reglages = SettingsStore(StockageMemoire()).apply {
            icomHote = "127.0.0.1"; icomPortControle = r.portControle; icomPortCiv = r.portCiv; icomPortAudio = r.portAudio
            icomUtilisateur = "x"; icomUtilisateurCode = "74496a33"; icomMdp = "y"; icomMdpCode = "262222745336364d"
        }
        val poste = PosteIcomReseau(reglages)
        assertEquals(null, poste.prends(PosteIcomReseau.Usage.CAT))
        val cat = CivController().apply { pacingMs = 20L; attach(poste.lienCiv()) }
        assertEquals(145_800_000L, cat.readFrequency())
        assertTrue(cat.setFrequency(435_795_000L))
        assertTrue(attends { r.frequenceHz == 435_795_000L })
        // The PTT never leaves SatMe.
        assertFalse(cat.setTransmit(true))
        assertTrue(poste.dernierRefus.contains("réception seule"))
        Thread.sleep(200)
        assertTrue(r.civRecues.none { it.size >= 7 && (it[4].toInt() and 0xFF) == 0x1C && it[6].toInt() == 1 })
        // Closing the CAT, the last user, logs out.
        cat.close()
        assertTrue(attends { r.jetonRendu })
        assertEquals(null, poste.session)
    }

    @Test fun audio_recu_et_paquet_perdu_redemande() {
        val r = radio(perdAudio = 5)
        val s = SessionIcom(config(r))
        val echantillons = AtomicLong()
        s.audioRecu = { pcm, taux -> assertEquals(16_000, taux); echantillons.addAndGet(pcm.size.toLong()) }
        assertTrue(s.ouvre())
        assertTrue(attends { echantillons.get() >= 16_000 })
        assertTrue("paquet perdu redemandé", r.reprisesAudio.contains(5))
        assertEquals(0L, s.tampon.perdus)
        assertTrue(s.statut.value.latenceMs >= 0)
        s.ferme()
        assertTrue(attends { r.jetonRendu })
    }

    @Test fun l_audio_arrive_en_48k_par_le_poste() {
        val r = radio()
        val reglages = SettingsStore(StockageMemoire()).apply {
            icomHote = "127.0.0.1"; icomPortControle = r.portControle; icomPortCiv = r.portCiv; icomPortAudio = r.portAudio
            icomUtilisateurCode = "74496a33"; icomMdpCode = "262222745336364d"
        }
        val poste = PosteIcomReseau(reglages)
        val n = AtomicLong()
        poste.auditeurs += { _, k, taux -> assertEquals(48_000, taux); n.addAndGet(k.toLong()) }
        assertEquals(null, poste.prends(PosteIcomReseau.Usage.AUDIO))
        assertTrue(attends { n.get() >= 48_000 })
        poste.rends(PosteIcomReseau.Usage.AUDIO)
        assertTrue(attends { r.jetonRendu })
    }

    @Test fun session_perdue_quand_le_poste_ferme() {
        val r = radio()
        val s = SessionIcom(config(r))
        assertTrue(s.ouvre())
        r.chasse()
        assertTrue(attends { s.statut.value.etat == SessionIcom.Etat.PERDU })
        assertFalse(s.connecte)
        assertNotNull(s.statut.value.message)
    }

    @Test fun identifiants_manquants() {
        val reglages = SettingsStore(StockageMemoire()).apply { icomHote = "127.0.0.1"; icomUtilisateur = "f4ioz"; icomMdp = "secret" }
        val poste = PosteIcomReseau(reglages)
        val (c, pourquoi) = poste.config()
        assertEquals(null, c)
        assertTrue(pourquoi.contains("coder"))
        // Learnt once from a trace, then coded without the hex.
        val table = CodageIdentifiants()
        table.apprends("f4ioz", CodageIdentifiants.hex("0102030405")!!)
        table.apprends("secret", CodageIdentifiants.hex("111213141516")!!)
        reglages.icomTableCodage = table.ecrit()
        assertNotNull(poste.config().first)
    }
}
