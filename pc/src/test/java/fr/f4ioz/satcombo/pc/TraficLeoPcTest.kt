/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.aprs.Trame
import fr.f4ioz.satcombo.data.Transmitter
import fr.f4ioz.satcombo.demo.ModulesStation
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * « Trafic satellite » on the simulated IC-9700: nothing keyed by itself,
 * each over keyed once and released, the module's guards on every keying,
 * SO-50's arming tone put back, the APRS beacon for one pass only. No port,
 * no card opened.
 */
class TraficLeoPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-trafic-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    private fun attends(ms: Long = 5_000, quoi: () -> Boolean) {
        val fin = System.currentTimeMillis() + ms
        while (!quoi()) { check(System.currentTimeMillis() < fin) { "trop long" }; Thread.sleep(20) }
    }

    private class Fausse : EmissionPostePc.Sortie {
        val recus = AtomicLong()
        override val nom = "fausse"
        override fun pousse(pcm: ShortArray, n: Int, taux: Int): String? { recus.addAndGet(n.toLong()); return null }
        override fun ferme() {}
    }

    private var t = 10_000_000L
    private val fausse = Fausse()
    private val rs44 = Transmitter("Linear transponder", "USB", 145_935_000L, 145_995_000L, 435_610_000L, 435_670_000L, true, true, "Transponder")
    private val so50 = Transmitter("Mode V/U FM - Voice Repeater CTCSS 67.0", "FM", 145_850_000L, null, 436_795_000L, null, false, true, "Transceiver")
    private val iss = Transmitter("APRS digipeater", "AFSK", 145_825_000L, null, 145_825_000L, null, false, true, "Transceiver", 1200.0)

    @Volatile private var sit = TraficLeoPc.Situation(44909, "RS-44", 35.0, rs44, 145_960_000L, t - 60_000L, t + 600_000L, 60.0)
    @Volatile private var ton = -1 to 0L
    private var rearmes = 0
    private val emis = mutableListOf<Trame>()
    private var module = true

    private fun station(): StationPc {
        val st = StationPc(StockageMemoire(), Catalogue(dossier, enLigne = false), PortsSeriePc(), portee)
        st.settings.manualLocator = "JN18FS"
        st.connecteCat("SIM", 0, 0)
        attends { st.etat.value.catConnecte && st.posteSimule != null }
        return st
    }

    private fun montage(): Triple<StationPc, EmissionPostePc, TraficLeoPc> {
        val st = station()
        val p = EmissionPostePc(st, { null }, { t }, { _, _ -> fausse })
        val f = TraficLeoPc(st.stockage, p, dossier, { sit }, { "F4IOZ" }, { "JN18FS" },
            positionAprs = { 48.8 to 2.3 }, aprsEmis = { tr, _ -> emis += tr },
            rearme = { rearmes++ }, tonArme = { ton }, horloge = { t }, simule = { false }, module = { module })
        p.gardeTrafic = { f.garde() }
        return Triple(st, p, f)
    }

    /** Runs the loop until the over is done (the clock moved 20 ms a turn). */
    private fun joueJusquauBout(f: TraficLeoPc, max: Int = 10_000) {
        var n = 0
        while (f.emet && n++ < max) { t += 20; f.vie(); f.tic() }
        check(!f.emet) { "pas fini" }
    }

    @Test fun rien_n_emet_tout_seul() {
        val (st, _, f) = montage()
        repeat(200) { t += 1_000; f.vie(); f.tic() }
        assertTrue(st.posteSimule!!.commandesPtt.isEmpty())
        assertFalse(f.emet)
    }

    @Test fun memoire_cw_une_fois_puis_relache() {
        val (st, _, f) = montage()
        f.vie()
        f.lui = "EA4XX"
        assertNull(f.envoieCw(2))
        assertTrue(st.posteSimule!!.transmitting)
        val attendu = f.pcmCw(fr.f4ioz.satcombo.domain.TraficSatellite.texteCw("EA4XX DE F4IOZ JN18 JN18 K")).size.toLong()
        joueJusquauBout(f)
        assertFalse(st.posteSimule!!.transmitting)
        assertEquals(attendu, fausse.recus.get())
        assertTrue(f.raison.startsWith("envoyé"))
        // On an FM repeater: refused, nothing keyed.
        sit = sit.copy(transpondeur = so50, monteeHz = 145_850_000L)
        assertNotNull(f.envoieCw(1))
        assertFalse(st.posteSimule!!.transmitting)
    }

    @Test fun module_coupe_rien() {
        val (st, p, f) = montage()
        module = false
        f.vie()
        assertTrue(f.envoieCw(1)!!.contains("désactivé"))
        // Its guards are gone too: the rig's own PTT as before, whatever the preset.
        f.reglage(mapOf("el" to "50"))
        assertNull(f.garde())
        assertNull(p.appuie("a", false, ""))
        p.relache("relâché")
        assertEquals(fr.f4ioz.satcombo.domain.TraficSatellite.AUTO, f.ctcssVoulu())
        assertFalse(st.posteSimule!!.transmitting)
    }

    @Test fun garde_d_elevation_sur_le_ptt_du_poste() {
        val (st, p, f) = montage()
        f.reglage(mapOf("el" to "40"))
        assertEquals(40, f.preset(44909).elevationMin)
        // Under 40°: the operator's PTT refused, nothing keyed.
        assertTrue(p.appuie("a", false, "")!!.contains("40°"))
        assertFalse(st.posteSimule!!.transmitting)
        // Above: keyed; falling under while keyed: released.
        sit = sit.copy(elevationDeg = 45.0)
        assertNull(p.appuie("a", false, ""))
        assertTrue(st.posteSimule!!.transmitting)
        sit = sit.copy(elevationDeg = 39.0)
        p.tic()
        assertFalse(st.posteSimule!!.transmitting)
        // The uplink out of the transponder: refused.
        sit = sit.copy(elevationDeg = 45.0, monteeHz = 144_300_000L)
        assertNotNull(p.appuie("a", false, ""))
    }

    @Test fun page_absente_arrete() {
        val (st, _, f) = montage()
        f.vie()
        assertNull(f.envoieCw(1))
        assertTrue(st.posteSimule!!.transmitting)
        // No heartbeat for longer than allowed: cut.
        repeat(400) { t += 20; f.tic() }
        assertFalse(f.emet)
        assertFalse(st.posteSimule!!.transmitting)
        assertTrue(f.erreur.contains("page"))
    }

    @Test fun armement_so50_et_tonalite_remise() {
        val (st, _, f) = montage()
        sit = TraficLeoPc.Situation(27607, "SO-50", 30.0, so50, 145_850_000L, t - 60_000L, t + 600_000L, 50.0)
        f.reglage(mapOf("ctcss" to "670"))
        assertEquals(670, f.ctcssVoulu())
        f.vie()
        assertNull(f.armeSo50())
        // The loop asked to arm 74.4 Hz; not keyed until it has.
        assertEquals(744, f.ctcssVoulu())
        val avant = rearmes
        t += 100; f.tic()
        assertFalse(st.posteSimule!!.transmitting)
        ton = 744 to 1L
        t += 100; f.tic()
        assertTrue(st.posteSimule!!.transmitting)
        joueJusquauBout(f)
        assertFalse(st.posteSimule!!.transmitting)
        // The satellite's own tone back.
        assertEquals(670, f.ctcssVoulu())
        assertTrue(rearmes > avant)
        // Cut during the carrier: the tone is put back as well.
        ton = -1 to 1L
        assertNull(f.armeSo50())
        ton = 744 to 2L
        t += 100; f.tic()
        assertTrue(f.emet)
        f.arrete()
        assertEquals(670, f.ctcssVoulu())
        assertFalse(st.posteSimule!!.transmitting)
    }

    @Test fun aprs_par_l_iss_et_balise_d_un_passage() {
        val (st, _, f) = montage()
        f.vie()
        // Not an APRS transmitter: refused.
        assertNotNull(f.envoieAprs("position", "", ""))
        sit = TraficLeoPc.Situation(25544, "ISS", 30.0, iss, 145_826_500L, t - 60_000L, t + 300_000L, 60.0)
        assertNull(f.envoieAprs("position", "", ""))
        joueJusquauBout(f)
        assertEquals(1, emis.size)
        assertEquals(listOf("ARISS"), emis[0].relais.map { it.indicatif })
        // Too soon after: refused.
        assertNotNull(f.envoieAprs("statut", "", "73"))
        // The beacon: armed for this pass, twice at most, gone at LOS.
        assertNull(f.armeBalise(60, 2))
        var n = 0
        while (t < sit.losMs!! + 5_000 && n++ < 100_000) { t += 20; f.vie(); f.tic() }
        assertEquals(3, emis.size)
        assertFalse(f.balise.armee)
        assertFalse(st.posteSimule!!.transmitting)
        // Armed again, then the page goes quiet: disarmed, nothing sent.
        sit = sit.copy(aosMs = t, losMs = t + 300_000L)
        assertNull(f.armeBalise(60, 4))
        t += 16_000
        repeat(1_000) { t += 20; f.tic() }
        assertFalse(f.balise.armee)
        assertEquals(3, emis.size)
    }

    @Test fun memoire_vocale_enregistree_puis_jouee() {
        val (st, _, f) = montage()
        val voix = ShortArray(16_000) { (Math.sin(it * 0.1) * 3000).toInt().toShort() }
        assertNull(f.enregistreVoix(1, "CQ", TraficLeoPc.wav(voix, 16_000)))
        assertNotNull(f.enregistreVoix(1, null, ByteArray(10)))
        assertEquals("CQ", f.nomVoix(1))
        f.vie()
        assertNull(f.joueVoix(1))
        joueJusquauBout(f)
        assertFalse(st.posteSimule!!.transmitting)
        // 1 s at 16 kHz, played at 48 kHz.
        assertTrue(fausse.recus.get() in 47_000L..48_100L)
        assertNotNull(f.joueVoix(2))
    }

    @Test fun module_trafic_desactive_par_defaut() {
        val st = StockageMemoire()
        assertTrue(ModulesStation.TRAFIC in ModulesStation.migre(st))
        assertFalse(ModulesStation.actif(st, ModulesStation.TRAFIC))
        // No profile switches it on.
        assertTrue(ModulesStation.TRAFIC in ModulesStation.profil("tout")!!)
    }
}
