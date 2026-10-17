/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.datv.Datv
import fr.f4ioz.satcombo.datv.Emetteur
import fr.f4ioz.satcombo.datv.PlanInterieur
import fr.f4ioz.satcombo.datv.ReglesEmission
import fr.f4ioz.satcombo.datv.s2.RecepteurDvbs2
import fr.f4ioz.satcombo.datv.s2.Rolloff
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import fr.f4ioz.satcombo.sdr.Bladerf
import fr.f4ioz.satcombo.sdr.FauxBladerf
import fr.f4ioz.satcombo.sdr.FauxHackrf
import fr.f4ioz.satcombo.sdr.HackrfEmission
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The DATV transmission from the page's side to the Pluto's samples, all
 * offline: a fake IIO daemon takes the samples, the test receiver decodes
 * them back to the transport stream.
 */
class EmissionDatvPcTest {

    /** An IIO daemon that keeps every TX sample written (at the DAC's pace). */
    private class FauxPluto(val debit: Double = 2_331_000.0) {
        val serveur = ServerSocket(0)
        val connexions = AtomicInteger()
        val commandes = CopyOnWriteArrayList<String>()
        val attributs = ConcurrentHashMap<String, String>()
        val echantillons = ByteArrayOutputStream()
        @Volatile var octets = 0L
        val port get() = serveur.localPort

        init {
            thread(isDaemon = true) { runCatching { while (true) { val c = serveur.accept(); connexions.incrementAndGet(); thread(isDaemon = true) { sert(c) } } } }
        }

        private fun ligne(e: InputStream): String? {
            val b = ByteArrayOutputStream()
            while (true) { val c = e.read(); if (c < 0) return null; if (c == '\n'.code) return b.toString("US-ASCII").trimEnd('\r'); b.write(c) }
        }

        private fun lis(e: InputStream, n: Int) = ByteArray(n).also { var k = 0; while (k < n) { val r = e.read(it, k, n - k); if (r < 0) error("fin"); k += r } }

        private fun sert(c: Socket) = runCatching {
            c.use {
                val e = BufferedInputStream(c.getInputStream()); val o = c.getOutputStream()
                while (true) {
                    val l = ligne(e) ?: break
                    val m = l.split(' ')
                    commandes += l
                    when (m[0]) {
                        "PRINT" -> { val x = XML.toByteArray(); o.write("${x.size}\n".toByteArray()); o.write(x); o.write('\n'.code) }
                        "WRITE" -> { val n = m.last().toInt(); attributs[m.drop(1).dropLast(1).joinToString(" ")] = String(lis(e, n)); o.write("$n\n".toByteArray()) }
                        "TIMEOUT", "OPEN", "CLOSE" -> o.write("0\n".toByteArray())
                        "WRITEBUF" -> {
                            val n = m[2].toInt()
                            o.write("$n\n".toByteArray()); o.flush()
                            val d = lis(e, n)
                            synchronized(echantillons) { echantillons.write(d) }
                            octets += n
                            Thread.sleep((n / 4 / debit * 1000).toLong())
                            o.write("$n\n".toByteArray())
                        }
                        else -> o.write("-22\n".toByteArray())
                    }
                    o.flush()
                }
            }
        }

        fun iq(): ShortArray {
            val b = synchronized(echantillons) { echantillons.toByteArray() }
            return ShortArray(b.size / 2) { ((b[2 * it].toInt() and 0xFF) or (b[2 * it + 1].toInt() shl 8)).toShort() }
        }

        val loEteint get() = attributs["ad9361-phy OUTPUT altvoltage1 powerdown"] == "1"
        fun close() = runCatching { serveur.close() }
    }

    /** A transport stream maker without ffmpeg: [n] numbered packets, then the pipe kept open. */
    private class TsSynthetique(val n: Int) : EmissionDatvPc.FabriqueTs {
        val paquets = ArrayList<ByteArray>()
        var demandes = 0
        var parametres: EmissionDatvPc.ParametresTs? = null
        override fun demarre(p: EmissionDatvPc.ParametresTs): EmissionDatvPc.Processus {
            demandes++; parametres = p
            val entree = PipedInputStream(1 shl 20)
            val sortie = PipedOutputStream(entree)
            val r = java.util.Random(7)
            for (k in 0 until n) paquets += ByteArray(188).also { r.nextBytes(it); it[0] = 0x47; it[1] = 0x01; it[2] = 0x00; it[3] = (0x10 or (k and 15)).toByte() }
            val vivant = java.util.concurrent.atomic.AtomicBoolean(true)
            thread(isDaemon = true) { runCatching { paquets.forEach { sortie.write(it) }; sortie.flush(); while (vivant.get()) Thread.sleep(50) } }
            return object : EmissionDatvPc.Processus {
                override val sortie: InputStream = entree
                override val vivant: Boolean get() = vivant.get()
                override fun arrete() { vivant.set(false); runCatching { sortie.close() } }
            }
        }
    }

    private val ouverts = ArrayList<FauxPluto>()
    @After fun fin() { ouverts.forEach { it.close() } }

    private fun attends(ms: Long, c: () -> Boolean): Boolean {
        val fin = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fin) { if (c()) return true; Thread.sleep(20) }
        return c()
    }

    private fun emission(p: FauxPluto, ts: EmissionDatvPc.FabriqueTs, indicatif: String = "F4IOZ", vieMs: Long = 15_000L,
                         reception: () -> EmissionDatvPc.Reception? = { null }, occupe: String? = null): EmissionDatvPc =
        EmissionDatvPc(StockageMemoire(), File(System.getProperty("java.io.tmpdir"), "satme-test-datv"), { indicatif },
            plutoHote = { "127.0.0.1" }, plutoPort = { p.port }, plutoOccupe = { occupe }, reception = reception, fabrique = ts, vieMs = vieMs)

    @Test
    fun rien_sans_confirmation_ni_indicatif_ni_dans_la_bande() {
        val p = FauxPluto().also { ouverts += it }
        val ts = TsSynthetique(10)
        val e = emission(p, ts)
        assertEquals("émission non confirmée", e.demarre(confirme = false, chargeFictive = false))
        e.frequenceKhz = 2_402_000L
        assertTrue(e.demarre(true, false)!!.contains("balise"))
        e.frequenceKhz = 2_409_750L
        assertTrue(emission(p, ts, indicatif = "").demarre(true, false)!!.contains("indicatif"))
        assertTrue(emission(p, ts, occupe = "l'ADALM-PLUTO reçoit").demarre(true, false)!!.contains("reçoit"))
        // Nothing reached the Pluto, ffmpeg never started.
        assertEquals(0, p.connexions.get())
        assertEquals(0, ts.demandes)
        assertFalse(e.etat.emet)
    }

    @Test
    fun bout_en_bout_jusqu_aux_echantillons_du_pluto() {
        val p = FauxPluto().also { ouverts += it }
        val ts = TsSynthetique(450)
        val e = emission(p, ts)
        e.modcodTexte = "QPSK 2/3"; e.srKs = 333; e.rolloff = Rolloff.A25; e.pilotes = true; e.courtes = false
        e.frequenceKhz = 2_409_750L; e.gainDb = -30.0
        assertNull(e.demarre(confirme = true, chargeFictive = false))
        assertEquals(e.debitTs(), ts.parametres!!.debitTs)
        assertEquals("F4IOZ", ts.parametres!!.indicatif)
        // 450 packets (less than the 2 s the stream buffer holds), of which the 15 whole frames carry 428.
        val ok = attends(8000) { e.vie(); (e.json().optLong("paquets")) >= 425 && p.octets > 4L * 2_331_000 * 2 }
        assertTrue(e.json().toString().take(600) + " octets " + p.octets, ok)
        e.arrete()
        assertFalse(e.etat.emet)
        assertTrue(p.loEteint)
        assertEquals("2409750000", p.attributs["ad9361-phy OUTPUT altvoltage1 frequency"])
        assertEquals("2331000", p.attributs["ad9361-phy OUTPUT voltage0 sampling_frequency"])
        assertEquals("-30.00", p.attributs["ad9361-phy OUTPUT voltage0 hardwaregain"])
        // The Pluto's samples, decoded back: the same packets, byte for byte.
        val rx = RecepteurDvbs2(Rolloff.A25, 7)
        val b = rx.decode(rx.symboles(p.iq()))
        assertTrue("${b.paquets.size} paquets", b.paquets.size >= 425)
        for (k in b.paquets.indices) assertArrayEquals("paquet $k", ts.paquets[k], b.paquets[k])
        assertEquals(0, b.crcPaquetsFaux)
        assertEquals(0, b.ldpcEchecs)
        assertEquals(setOf(6), b.modcods)
    }

    @Test
    fun la_page_muette_arrete_l_emission() {
        val p = FauxPluto().also { ouverts += it }
        val e = emission(p, TsSynthetique(50), vieMs = 400L)
        assertNull(e.demarre(true, false))
        assertTrue(attends(3000) { e.tic(); !e.etat.emet })
        assertTrue(e.etat.raison, e.etat.raison.contains("page"))
        assertTrue(p.loEteint)
    }

    @Test
    fun charge_fictive_toute_frequence_gain_au_plus_moins_40() {
        val p = FauxPluto(debit = 2_450_000.0).also { ouverts += it }
        val e = emission(p, TsSynthetique(50))
        e.frequenceKhz = 2_450_000L; e.gainDb = -10.0; e.srKs = 250
        assertNotNull(e.demarre(true, chargeFictive = false))
        assertNull(e.demarre(true, chargeFictive = true))
        assertTrue(attends(2000) { p.attributs["ad9361-phy OUTPUT voltage0 hardwaregain"] != null })
        assertEquals("-40.00", p.attributs["ad9361-phy OUTPUT voltage0 hardwaregain"])
        // Raised from the page: still held.
        e.regleGain(0.0)
        assertEquals("-40.00", p.attributs["ad9361-phy OUTPUT voltage0 hardwaregain"])
        e.arrete()
        assertTrue(p.loEteint)
    }

    @Test
    fun boucle_fermee_sur_mesures_simulees() {
        val p = FauxPluto().also { ouverts += it }
        var vue: EmissionDatvPc.Reception? = null
        val e = emission(p, TsSynthetique(50), reception = { vue })
        e.frequenceKhz = 2_409_750L; e.gainDb = -20.0
        // The MiniTiouner on the beacon first: its MER noted.
        vue = EmissionDatvPc.Reception(true, 12.0, Datv.BALISE_KHZ, "QO-100")
        e.tic()
        assertEquals(12.0, e.merBalise!!, 0.0)
        assertNull(e.demarre(true, false))
        // Then on our downlink, 2 dB above the beacon: the gain stepped down.
        vue = EmissionDatvPc.Reception(true, 14.0, 10_499_250L, "F4IOZ")
        e.vie(); e.tic()
        assertTrue(e.boucle.etat.tropFort)
        assertEquals("-21.00", p.attributs["ad9361-phy OUTPUT voltage0 hardwaregain"])
        assertTrue(e.json().getJSONObject("boucle").getString("message").startsWith("TROP FORT"))
        e.arrete()
    }

    // ------------------------------------------------------------ HackRF and the indoor test (a fake hackrf_transfer only)

    private val fauxHackrf = ArrayList<FauxHackrf>()
    @After fun finHackrf() { HackrfEmission.arreteTout(); fauxHackrf.forEach { it.close() } }

    private fun hackrf(f: FauxHackrf?, ts: EmissionDatvPc.FabriqueTs, indicatif: String = "F4IOZ"): EmissionDatvPc {
        assumeTrue("sh absent", File("/bin/sh").canExecute())
        // Never the PATH: no real hackrf_transfer, ever.
        val e = EmissionDatvPc(StockageMemoire(), File(System.getProperty("java.io.tmpdir"), "satme-test-datv"), { indicatif },
            plutoHote = { "127.0.0.1" }, plutoPort = { 9 }, fabrique = ts, outilParDefaut = { null })
        e.emetteur = Emetteur.HACKRF
        if (f != null) { fauxHackrf += f; e.hackrfOutil = f.dossier.path }
        return e
    }

    @Test
    fun hackrf_jamais_vers_qo100_ni_sans_outil() {
        val f = FauxHackrf()
        val ts = TsSynthetique(10)
        val e = hackrf(f, ts)
        e.frequenceKhz = 2_409_750L
        assertEquals(ReglesEmission.HACKRF_PAS_QO100, e.demarre(confirme = true, chargeFictive = false))
        assertEquals("émission non confirmée", e.demarre(confirme = false, chargeFictive = false, interieur = true))
        e.frequenceInterieurKhz = 1_800_000L
        assertTrue(e.demarre(true, false, interieur = true)!!.contains("1240–1300"))
        assertTrue(hackrf(f, ts, indicatif = "").demarre(true, false, interieur = true)!!.contains("indicatif"))
        // Tools missing: said how to install them.
        assertTrue(hackrf(null, ts).demarre(true, false, interieur = true)!!.contains("apt install hackrf"))
        // Nothing started.
        assertTrue(f.args.isEmpty())
        assertEquals(0, ts.demandes)
        // The gain asked is held at 10 dB.
        e.vgaHackrf = 40
        assertEquals(ReglesEmission.HACKRF_VGA_MAX, e.vgaHackrf)
    }

    @Test
    fun hackrf_detection_lecture_seule() {
        val f = FauxHackrf()
        val e = hackrf(f, TsSynthetique(10))
        val i = e.detecteHackrf()
        assertEquals("HackRF One · n° …234e925f · firmware 2024.02.1", i.cartes.single().libelle)
        assertTrue(f.args.isEmpty())
        f.mode = "absent"
        assertTrue(e.detecteHackrf().message.startsWith("HackRF absent"))
        assertTrue(hackrf(null, TsSynthetique(1)).detecteHackrf().message.contains("hackrf_info introuvable"))
        val j = e.json()
        assertEquals("hackrf", j.getString("emetteur"))
        assertEquals(1_255_000L, j.getLong("intKhz"))
        assertEquals("", j.getString("intRefus"))
    }

    @Test
    fun hackrf_essai_en_interieur_bout_en_bout() {
        val f = FauxHackrf(limite = 8_000_000L)
        val ts = TsSynthetique(450)
        val e = hackrf(f, ts)
        e.modcodTexte = "QPSK 2/3"; e.srKs = 333; e.rolloff = Rolloff.A25; e.pilotes = true; e.courtes = false
        e.vgaHackrf = 8; e.dureeInterieurS = 300
        assertTrue(e.hackrfDecalage)
        e.hackrfDecalage = false
        assertEquals(1_255_000L, e.frequenceInterieurKhz)
        e.detecteHackrf()
        assertNull(e.demarre(confirme = true, chargeFictive = false, interieur = true))
        assertTrue(e.etat.interieur)
        assertTrue(attends(10_000) { e.vie(); f.compte >= 8_000_000L })
        val j = e.json()
        assertTrue(j.getBoolean("emet"))
        assertEquals(8.0, j.getDouble("gainApplique"), 0.0)
        assertTrue(j.getLong("restant") in 295L..300L)
        // The closed loop on the beacon stays out of it.
        e.tic()
        assertEquals(0, e.boucle.etat.baisses)
        e.arrete()
        assertFalse(e.etat.emet)
        assertTrue(attends(2000) { f.finEntree })
        assertEquals(listOf("-d", "a06063c8234e925f", "-t", "-", "-f", "1255000000", "-s", "2331000", "-b", "1750000", "-x", "8", "-a", "0", "-p", "0"), f.args)
        assertEquals(0, HackrfEmission.enCours())
        // The HackRF's 8-bit samples, decoded back: the same packets.
        val b8 = f.iq
        val iq = ShortArray(b8.size) { (b8[it] * 256).toShort() }
        val rx = RecepteurDvbs2(Rolloff.A25, 7)
        val b = rx.decode(rx.symboles(iq))
        assertTrue("${b.paquets.size} paquets", b.paquets.size >= 400)
        for (k in b.paquets.indices) assertArrayEquals("paquet $k", ts.paquets[k], b.paquets[k])
        assertEquals(0, b.crcPaquetsFaux)
        assertEquals(0, b.ldpcEchecs)
    }

    @Test
    fun hackrf_oscillateur_decale_le_signal_reste_sur_sa_frequence() {
        val f = FauxHackrf(limite = 8_000_000L)
        val ts = TsSynthetique(450)
        val e = hackrf(f, ts)
        e.modcodTexte = "QPSK 2/3"; e.srKs = 333; e.rolloff = Rolloff.A25; e.pilotes = true; e.courtes = false
        val j0 = e.json().getJSONObject("decalage")
        assertEquals(1_254_500.0, j0.getDouble("loKhz"), 0.0)
        assertEquals("", j0.getString("refus"))
        assertNull(e.demarre(confirme = true, chargeFictive = false, interieur = true))
        // The band rules on the signal itself.
        assertEquals(1_255_000L, e.etat.frequenceKhz)
        assertTrue(attends(10_000) { e.vie(); f.compte >= 8_000_000L })
        e.arrete()
        assertTrue(attends(2000) { f.finEntree })
        // The LO 500 kHz below, the same rate and filter.
        assertEquals(listOf("-t", "-", "-f", "1254500000", "-s", "2331000", "-b", "1750000", "-x", "0", "-a", "0", "-p", "0"), f.args)
        // Mixed back down by the receiver's side: the same packets.
        val b8 = f.iq
        val iq = ShortArray(b8.size) { (b8[it] * 256).toShort() }
        fr.f4ioz.satcombo.sdr.Melangeur(-500_000.0, 2_331_000.0).applique(iq)
        val rx = RecepteurDvbs2(Rolloff.A25, 7)
        val b = rx.decode(rx.symboles(iq))
        assertTrue("${b.paquets.size} paquets", b.paquets.size >= 400)
        for (k in b.paquets.indices) assertArrayEquals("paquet $k", ts.paquets[k], b.paquets[k])
        assertEquals(0, b.ldpcEchecs)
        // At the top of 13 cm: the LO and the image go below the signal, inside the band.
        e.srKs = 2000; e.rolloff = Rolloff.A35; e.frequenceInterieurKhz = 2_448_600L
        val d = e.json().getJSONObject("decalage")
        assertEquals(2_446_980.0, d.getDouble("loKhz"), 0.0)
        assertEquals(8_000_000L, e.json().getLong("echantillons"))
    }

    @Test
    fun hackrf_absent_au_demarrage_message_clair() {
        val f = FauxHackrf("absent")
        val ts = TsSynthetique(10)
        val e = hackrf(f, ts)
        val r = e.demarre(true, chargeFictive = true)
        assertTrue(r ?: "null", r!!.startsWith("HackRF absent"))
        assertFalse(e.etat.emet)
        assertEquals(0, HackrfEmission.enCours())
    }

    @Test
    fun hackrf_qui_meurt_arrete_l_emission() {
        val f = FauxHackrf("meurt")
        val e = hackrf(f, TsSynthetique(50))
        assertNull(e.demarre(true, false, interieur = true))
        assertTrue(attends(5000) { e.vie(); e.tic(); !e.etat.emet })
        assertTrue(e.etat.erreur, e.etat.erreur.contains("hackrf_transfer"))
        assertEquals(0, HackrfEmission.enCours())
    }

    @Test
    fun pluto_essai_en_interieur_gain_au_plus_moins_40() {
        val p = FauxPluto(debit = 2_331_000.0).also { ouverts += it }
        val e = emission(p, TsSynthetique(50))
        e.gainDb = -10.0; e.srKs = 333; e.frequenceKhz = 2_409_750L
        e.frequenceInterieurKhz = 2_320_000L
        assertNull(e.demarre(true, chargeFictive = false, interieur = true))
        assertTrue(attends(2000) { p.attributs["ad9361-phy OUTPUT voltage0 hardwaregain"] != null })
        assertEquals("-40.00", p.attributs["ad9361-phy OUTPUT voltage0 hardwaregain"])
        assertEquals("2320000000", p.attributs["ad9361-phy OUTPUT altvoltage1 frequency"])
        e.regleGain(0.0)
        assertEquals("-40.00", p.attributs["ad9361-phy OUTPUT voltage0 hardwaregain"])
        assertTrue(e.json().getLong("restant") <= PlanInterieur.DUREE_DEFAUT_S)
        e.arrete()
        assertTrue(p.loEteint)
        // Outside the amateur bands: refused before the Pluto is reached.
        val avant = p.connexions.get()
        e.frequenceInterieurKhz = 2_460_000L
        assertNotNull(e.demarre(true, false, interieur = true))
        assertEquals(avant, p.connexions.get())
    }

    @Test
    fun ffmpeg_reel_service_au_nom_de_l_indicatif() {
        val ff = RenduPc.ffmpeg()?.takeIf { File(it).canExecute() }
        val probe = ff?.let { File(File(it).parentFile, "ffprobe") }?.takeIf { it.canExecute() }
        assumeTrue("ffmpeg absent", ff != null && probe != null)
        val p = FauxPluto().also { ouverts += it }
        val e = emission(p, EmissionDatvPc.FfmpegTs())
        e.modcodTexte = "QPSK 2/3"; e.srKs = 333; e.rolloff = Rolloff.A35; e.frequenceKhz = 2_409_750L; e.source = "mire"
        assertNull(e.demarre(true, false))
        assertTrue(attends(15_000) { e.vie(); e.json().optLong("paquets") > 700 })
        val j = e.json()
        e.arrete()
        assertTrue(p.loEteint)
        println("DATV émission (ffmpeg réel) : TS ${j.optLong("debitTs")} bit/s, ${j.optLong("paquets")} paquets, " +
            "%.0f %% de trames vides, CPU %.0f %%".format(j.optDouble("tramesVidesPct"), j.optDouble("cpu")))
        val rx = RecepteurDvbs2(Rolloff.A35, 7)
        val b = rx.decode(rx.symboles(p.iq()))
        assertEquals(0, b.crcPaquetsFaux)
        val f = File.createTempFile("satme-datv", ".ts")
        f.outputStream().use { o -> b.paquets.forEach { o.write(it) } }
        val sortie = ProcessBuilder(probe!!.path, "-hide_banner", f.path).redirectErrorStream(true).start().inputStream.bufferedReader().readText()
        f.delete()
        assertTrue(sortie, sortie.contains("service_name    : F4IOZ"))
        assertTrue(sortie, sortie.contains("h264"))
        // The station card, through the whole DVB-S2 chain, in place of null packets.
        val l = fr.f4ioz.satcombo.datv.LecteurCarte()
        b.paquets.forEach { l.bloc(it) }
        assertEquals("F4IOZ", l.carte?.indicatif)
        assertEquals(2_409_750L, l.carte?.frequenceKhz)
        assertEquals("QPSK 2/3", l.carte?.modcod)
    }

    // ------------------------------------------------------------ the MODCOD fitted to our own reception

    /** Records each ffmpeg asked for, and lets [dedans] make it. */
    private class Releve(val dedans: EmissionDatvPc.FabriqueTs) : EmissionDatvPc.FabriqueTs {
        val demandes = CopyOnWriteArrayList<EmissionDatvPc.ParametresTs>()
        override fun demarre(p: EmissionDatvPc.ParametresTs): EmissionDatvPc.Processus { demandes += p; return dedans.demarre(p) }
    }

    private fun adaptative(p: FauxPluto, ts: EmissionDatvPc.FabriqueTs, horloge: () -> Long, vue: () -> EmissionDatvPc.Reception?) =
        EmissionDatvPc(StockageMemoire(), File(System.getProperty("java.io.tmpdir"), "satme-test-datv"), { "F4IOZ" },
            plutoHote = { "127.0.0.1" }, plutoPort = { p.port }, reception = vue, fabrique = ts, horloge = horloge,
            nouvelleAdaptation = { fr.f4ioz.satcombo.datv.AdaptationModcod(monteeMs = 3_000L, attenteMs = 2_000L, perteBoucleMs = 3_000L) })

    private fun debit(m: String) = fr.f4ioz.satcombo.datv.s2.Modcod.de(m, false, true)!!.debitTs(333_000.0).toLong()

    @Test
    fun modcod_adapte_sur_mesures_simulees() {
        val p = FauxPluto().also { ouverts += it }
        val ts = Releve(TsSynthetique(2000))
        var t = 1_000_000L
        var vue: EmissionDatvPc.Reception? = EmissionDatvPc.Reception(true, 9.0, 10_499_250L, "F4IOZ")
        val e = adaptative(p, ts, { t }, { vue })
        e.modcodTexte = "QPSK 1/2"; e.srKs = 333; e.pilotes = true; e.frequenceKhz = 2_409_750L; e.gainDb = -30.0
        assertFalse(e.adaptOn)
        assertNull(e.demarre(true, false))
        fun secondes(n: Int) = repeat(n) { t += 1000; e.vie(); e.tic() }
        assertTrue(attends(8000) { e.vie(); e.json().optLong("paquets") > 100 })
        // Off by default: 9 dB of MER and nothing changes.
        secondes(10)
        assertEquals(1, ts.demandes.size)
        assertEquals("Désactivée", e.json().getJSONObject("adapt").let { if (it.getBoolean("actif")) "active" else "Désactivée" })
        // On: after the slow climb, 8PSK 2/3 (6.62 dB + 2 dB of margin under 9 dB), a new ffmpeg at its rate.
        e.adaptOn = true
        secondes(5)
        assertEquals(2, ts.demandes.size)
        assertEquals(debit("8PSK 2/3"), ts.demandes[1].debitTs)
        assertEquals(debit("8PSK 2/3"), e.etat.debitTs)
        assertEquals("F4IOZ", ts.demandes[1].indicatif)
        val j = e.json()
        assertEquals("8PSK 2/3", j.getString("modcodEnCours"))
        assertEquals("QPSK 1/2", j.getString("modcod"))   // the operator's choice kept
        assertEquals("8PSK 2/3", j.getJSONObject("adapt").getJSONArray("historique").getJSONObject(0).getString("vers"))
        assertTrue(attends(8000) { e.vie(); e.json().optLong("paquets") > 150 })
        // Packets lost: down at once (two seconds), at least one step.
        vue = EmissionDatvPc.Reception(true, 9.0, 10_499_250L, "F4IOZ", pertes = true)
        secondes(4)
        assertEquals(3, ts.demandes.size)
        assertTrue(ts.demandes[2].debitTs < debit("8PSK 2/3"))
        assertTrue(attends(8000) { e.vie(); e.json().optLong("paquets") > 150 })
        // Our signal gone (another service): after 3 s, the most robust allowed, then frozen.
        vue = EmissionDatvPc.Reception(true, 9.0, 10_499_250L, "F1ABC")
        secondes(2)
        assertTrue(e.json().getJSONObject("adapt").getString("prochaine").contains("perdu"))
        secondes(3)
        assertEquals(4, ts.demandes.size)
        assertEquals(debit("QPSK 1/4"), ts.demandes[3].debitTs)
        secondes(20)
        assertEquals(4, ts.demandes.size)
        assertTrue(e.json().getJSONObject("adapt").getBoolean("fige"))
        // Never the frequency, the rate, the gain: the Pluto's settings as at the start.
        assertEquals("2409750000", p.attributs["ad9361-phy OUTPUT altvoltage1 frequency"])
        assertEquals("2331000", p.attributs["ad9361-phy OUTPUT voltage0 sampling_frequency"])
        assertEquals("-30.00", p.attributs["ad9361-phy OUTPUT voltage0 hardwaregain"])
        assertTrue(attends(8000) { e.vie(); e.json().optLong("paquets") > 100 })
        assertTrue(e.etat.emet)
        e.arrete()
        assertTrue(p.loEteint)
        // The samples carry each MODCOD in turn, switched at frame boundaries: every frame whole, every one decoded.
        val rx = RecepteurDvbs2(Rolloff.A25, 7)
        val b = rx.decode(rx.symboles(p.iq()))
        val codes = listOf("QPSK 1/2", "8PSK 2/3", "QPSK 1/4").map { fr.f4ioz.satcombo.datv.s2.Modcod.de(it)!!.code }
        assertTrue("${b.modcods} ⊇ $codes", b.modcods.containsAll(codes))
        assertEquals(4, b.modcods.size)
        assertEquals(0, b.ldpcEchecs)
    }

    @Test
    fun ffmpeg_reel_changement_de_modcod_nouveau_muxrate() {
        val ff = RenduPc.ffmpeg()?.takeIf { File(it).canExecute() }
        assumeTrue("ffmpeg absent", ff != null)
        val p = FauxPluto().also { ouverts += it }
        val ts = Releve(EmissionDatvPc.FfmpegTs())
        var t = 1_000_000L
        val e = adaptative(p, ts, { t }, { EmissionDatvPc.Reception(true, 9.0, 1_255_000L, "", carteIndicatif = "F4IOZ") })
        e.modcodTexte = "QPSK 1/2"; e.srKs = 333; e.rolloff = Rolloff.A25; e.pilotes = true; e.source = "mire"; e.son = false
        e.frequenceInterieurKhz = 1_255_000L; e.adaptOn = true
        assertNull(e.demarre(true, false, interieur = true))
        assertTrue(attends(15_000) { e.vie(); e.json().optLong("paquets") > 300 })
        repeat(5) { t += 1000; e.vie(); e.tic() }
        assertEquals(2, ts.demandes.size)
        val mux = EmissionDatvPc.commande("ffmpeg", ts.demandes[1]).let { it[it.indexOf("-muxrate") + 1] }
        assertEquals(debit("8PSK 2/3").toString(), mux)
        // The new ffmpeg feeds the new modulator, the transmission never stopped.
        assertTrue(attends(15_000) { e.vie(); e.json().optLong("paquets") > 400 })
        assertTrue(e.etat.emet)
        e.arrete()
        val rx = RecepteurDvbs2(Rolloff.A25, 7)
        val b = rx.decode(rx.symboles(p.iq()))
        assertTrue(b.modcods.toString(), b.modcods.contains(fr.f4ioz.satcombo.datv.s2.Modcod.de("8PSK 2/3")!!.code))
    }

    @Test
    fun chat_seulement_pendant_une_emission_et_pas_trop_vite() {
        val p = FauxPluto().also { ouverts += it }
        var t = 1_000_000L
        val e = EmissionDatvPc(StockageMemoire(), File(System.getProperty("java.io.tmpdir"), "satme-test-datv"), { "F4IOZ" },
            plutoHote = { "127.0.0.1" }, plutoPort = { p.port }, fabrique = TsSynthetique(50), horloge = { t })
        e.frequenceKhz = 2_409_750L
        // Typing never transmits: refused with no transmission running, and nothing started.
        assertNotNull(e.dis("bonjour"))
        assertEquals(0, p.connexions.get())
        assertFalse(e.etat.emet)
        assertNull(e.demarre(true, false))
        assertNull(e.dis("bonjour"))
        t += 500
        assertNotNull(e.dis("trop vite"))
        t += 2000
        assertNotNull(e.dis("x".repeat(141)))
        assertNull(e.dis("é".repeat(140)))
        assertEquals(listOf("bonjour", "é".repeat(140)), e.dits().map { it.texte })
        assertEquals(e.dits()[0].seq + 1, e.dits()[1].seq)
        e.arrete()
        t += 5000
        assertNotNull(e.dis("après"))
    }

    // ------------------------------------------------------------ bladeRF (a fake bladeRF-cli only)

    private val fauxBladerf = ArrayList<FauxBladerf>()
    @After fun finBladerf() { Bladerf.arreteTout(); fauxBladerf.forEach { it.close() } }

    private fun bladerf(f: FauxBladerf?, ts: EmissionDatvPc.FabriqueTs, occupe: String? = null,
                        reception: () -> EmissionDatvPc.Reception? = { null }): EmissionDatvPc {
        assumeTrue("sh ou mkfifo absent", File("/bin/sh").canExecute() && File("/usr/bin/mkfifo").canExecute())
        // Never the PATH: no real bladeRF-cli, ever.
        val e = EmissionDatvPc(StockageMemoire(), File(System.getProperty("java.io.tmpdir"), "satme-test-datv"), { "F4IOZ" },
            plutoHote = { "127.0.0.1" }, plutoPort = { 9 }, plutoOccupe = { "le Pluto ne doit pas compter" }, bladerfOccupe = { occupe },
            bladerfProgramme = { f?.cli?.path }, reception = reception, fabrique = ts)
        e.emetteur = Emetteur.BLADERF
        if (f != null) fauxBladerf += f
        return e
    }

    @Test
    fun bladerf_vers_qo100_bout_en_bout_gain_bas_par_defaut() {
        val f = FauxBladerf(limite = 8_000_000L)
        val ts = TsSynthetique(300)
        val e = bladerf(f, ts)
        e.modcodTexte = "QPSK 2/3"; e.srKs = 333; e.rolloff = Rolloff.A25; e.pilotes = true; e.courtes = false
        e.frequenceKhz = 2_409_750L
        // Its own gain, low by default; the Pluto's untouched.
        assertEquals(EmissionDatvPc.GAIN_BLADERF_DEFAUT_DB, e.json().getDouble("gain"), 0.0)
        e.regleGain(-45.0)
        assertEquals(-45.0, e.gainBladerf, 0.0)
        assertEquals(ReglesEmission.GAIN_DEFAUT_DB, e.gainDb, 0.0)
        assertEquals(bladerf(null, ts).demarre(true, false), e.conseilBladerf())
        val i = e.detecteBladerf()
        assertEquals("aac46df92fb64a47a150cc2ab9b40e1f", i.carte!!.serie)
        assertNull(e.demarre(confirme = true, chargeFictive = false))
        assertTrue(attends(10_000) { e.vie(); f.compte >= 8_000_000L })
        val j = e.json()
        assertTrue(j.getBoolean("emet"))
        assertEquals("bladerf", j.getString("emetteurEnCours"))
        assertEquals(-45.0, j.getDouble("gainApplique"), 0.0)
        // While sending: a new gain goes to the bladeRF, under the ceiling of Réglages (−10 dB).
        assertEquals(-10.0, e.regleGain(0.0)!!, 0.0)
        e.arrete()
        assertFalse(e.etat.emet)
        assertEquals("off", f.txActif)
        assertEquals(0, Bladerf.enCours())
        val c = f.commandes
        assertEquals(listOf("set gain tx1 21", "set frequency tx1 2409750000", "set samplerate tx1 2331000", "set bandwidth tx1 2331000"), c.take(4))
        assertTrue(c.toString(), c.containsAll(listOf("tx start", "set gain tx1 56", "tx stop", "quit")))
        assertTrue(f.args.last().startsWith("-d *:serial=aac46df92fb64a47a150cc2ab9b40e1f -i"))
        // Its SC16 Q11 samples, decoded back: the same packets.
        val b = f.iq
        val iq = ShortArray(b.size / 2) { (((b[2 * it].toInt() and 0xff) or (b[2 * it + 1].toInt() shl 8)) * 16).toShort() }
        val rx = RecepteurDvbs2(Rolloff.A25, 7)
        val d = rx.decode(rx.symboles(iq))
        assertTrue("${d.paquets.size} paquets", d.paquets.size >= 200)
        for (k in d.paquets.indices) assertArrayEquals("paquet $k", ts.paquets[k], d.paquets[k])
        assertEquals(0, d.ldpcEchecs)
    }

    @Test
    fun bladerf_interieur_et_charge_bride_a_moins_40_occupe_refuse() {
        val f = FauxBladerf()
        val e = bladerf(f, TsSynthetique(50))
        e.gainMaxDb = 0.0
        e.regleGain(0.0)
        e.frequenceInterieurKhz = 2_320_000L
        assertNull(e.demarre(true, chargeFictive = false, interieur = true))
        assertTrue(e.etat.interieur)
        assertEquals(-40.0, e.json().getDouble("gainApplique"), 0.0)
        assertEquals(-40.0, e.regleGain(0.0)!!, 0.0)
        e.arrete()
        assertTrue(f.commandes.filter { it.startsWith("set gain") }.all { it == "set gain tx1 26" })
        assertNull(e.demarre(true, chargeFictive = true))
        assertEquals(-40.0, e.json().getDouble("gainApplique"), 0.0)
        e.arrete()
        assertEquals(0, Bladerf.enCours())
        // The bladeRF receiving (SDR tab): refused, nothing started; the Pluto's state does not count.
        val n = f.commandes.size
        assertEquals("le bladeRF reçoit", bladerf(f, TsSynthetique(5), occupe = "le bladeRF reçoit").demarre(true, false, interieur = true))
        assertEquals(n, f.commandes.size)
    }

    @Test
    fun bladerf_boucle_fermee_sur_la_balise() {
        val f = FauxBladerf()
        var r: EmissionDatvPc.Reception? = EmissionDatvPc.Reception(true, 8.0, Datv.BALISE_KHZ, "QO-100")
        val e = bladerf(f, TsSynthetique(50), reception = { r })
        e.frequenceKhz = 2_409_750L
        e.gainMaxDb = 0.0
        e.regleGain(-20.0)
        e.tic()
        assertNotNull(e.merBalise)
        assertNull(e.demarre(true, false))
        // Our own signal stronger than the beacon: the gain stepped down by 1 dB.
        r = EmissionDatvPc.Reception(true, 9.0, 2_409_750L + fr.f4ioz.satcombo.datv.PlanQo100.DECALAGE_KHZ, "F4IOZ")
        e.vie(); e.tic()
        assertEquals(-21.0, e.json().getDouble("gainApplique"), 0.0)
        assertTrue(f.commandes.contains("set gain tx1 45"))
        e.arrete()
        assertEquals(0, Bladerf.enCours())
    }

    companion object {
        val XML = """<?xml version="1.0" encoding="utf-8"?><context name="local">""" +
            """<device id="iio:device0" name="ad9361-phy" ></device>""" +
            """<device id="iio:device2" name="cf-ad9361-dds-core-lpc" ><channel id="voltage0" type="output" ><scan-element index="0" format="le:S16/16&gt;&gt;0" /></channel>""" +
            """<channel id="voltage1" type="output" ><scan-element index="1" format="le:S16/16&gt;&gt;0" /></channel></device>""" +
            """<device id="iio:device3" name="cf-ad9361-lpc" ></device></context>"""
    }
}
