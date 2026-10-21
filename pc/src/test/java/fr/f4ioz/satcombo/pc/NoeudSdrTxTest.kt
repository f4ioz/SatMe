/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.plateforme.StockageMemoire
import fr.f4ioz.satcombo.sdr.Dsp
import fr.f4ioz.satcombo.sdr.EmetteurNoeud
import fr.f4ioz.satcombo.sdr.EtatEmission
import fr.f4ioz.satcombo.sdr.FluxNoeud
import fr.f4ioz.satcombo.sdr.SourceIq
import fr.f4ioz.satcombo.sdr.SourceNoeudIq
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * A SatMe-Node in this process with fake boards: its SDR feeding SatMe-PC's
 * QO-100 reception (the beacon seen where it is, through the node), the
 * connection lost and taken up again; its transmitter behind « émission
 * autorisée » (off by default) and the watchdog. Nothing radiates: the
 * transmitter is a counter.
 */
class NoeudSdrTxTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-noeud-sdr-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stockageNoeud = StockageMemoire()
    @Volatile private var fini = false
    private val fermes = CopyOnWriteArrayList<String>()

    /** The node's SDR: the QO-100 beacon (absolute IF) 300 Hz off, wherever it is tuned, at the reception's rate. */
    private val baliseFausseHz = 739_750_300L
    @Volatile private var centreFaux = 0L

    private inner class FauxSdr : SourceIq {
        @Volatile var ouvert = true
        private val r = Random(2)
        private var phase = 0.0
        private var t0 = System.nanoTime(); private var paires = 0L
        override val iq16 = true
        override fun setSampleRate(rate: Int) = rate.toDouble()
        override fun setFreqCorrection(ppm: Int) {}
        override fun setCenterFreq(hz: Long): Long { centreFaux = hz; return hz }
        override fun setGain(tenthDb: Int?) {}
        override fun setAgc(on: Boolean) {}
        override fun read(buf: ByteArray, timeoutMs: Int) = throw UnsupportedOperationException()
        override fun read16(buf: ShortArray, timeoutMs: Int): Int {
            if (!ouvert) return -1
            // At about the board's pace.
            val du = (paires * 1e9 / Dsp.RTL_RATE).toLong() - (System.nanoTime() - t0)
            if (du > 0) Thread.sleep(du / 1_000_000, (du % 1_000_000).toInt())
            val pas = 2 * Math.PI * (baliseFausseHz - centreFaux) / Dsp.RTL_RATE
            for (i in 0 until buf.size / 2) {
                phase += pas
                buf[2 * i] = (6000 * Math.cos(phase) + r.nextDouble(-1500.0, 1500.0)).toInt().toShort()
                buf[2 * i + 1] = (6000 * Math.sin(phase) + r.nextDouble(-1500.0, 1500.0)).toInt().toShort()
            }
            paires += buf.size / 2
            return buf.size
        }
        override fun close() { ouvert = false; fermes += "sdr" }
    }

    /** The node's transmitter: counts what it is given, checks its guard as the real ones do; never radiates. */
    private inner class FauxTx : EmissionNoeudPc.EmetteurLocal {
        @Volatile var etat = EtatEmission()
        val recus = AtomicLong()
        @Volatile var demarre = 0
        override val libelle = "Pluto d'essai"
        override val nom = "Pluto d'essai"
        override fun etatCommun() = etat
        override fun arrete(raison: String) { if (etat.emet) etat = etat.copy(emet = false, raison = raison) }
        override fun regleGain(db: Double): Double? { etat = etat.copy(gainDb = db); return db }
        override fun demarre(hz: Long, debit: Long, gainDb: Double, dureeMs: Long, bandeHz: Long, plafondDb: Double, paires: Int,
                             garde: () -> String?, producteur: (ShortArray) -> Unit): String? {
            demarre++
            etat = EtatEmission(emet = true, frequenceHz = hz, gainDb = gainDb, finMs = System.currentTimeMillis() + dureeMs)
            thread(isDaemon = true) {
                val b = ShortArray(2 * paires)
                var derniere = 0L
                while (etat.emet) {
                    producteur(b)
                    recus.addAndGet(b.size.toLong())
                    val t = System.currentTimeMillis()
                    if (t - derniere >= 50) { derniere = t; garde()?.let { arrete(it) } }
                }
            }
            return null
        }
    }

    private val faux = CopyOnWriteArrayList<FauxSdr>()
    private val fauxTx = FauxTx()
    private val sdrNoeud = SdrNoeudPc(stockageNoeud, ouvre = { FauxSdr().also { faux += it } }, silenceMs = 1_500L)
    private val txNoeud = EmissionNoeudPc(stockageNoeud, { _, _ -> fauxTx }, occupe = { a -> if (sdrNoeud.tient == a) "occupé par le SDR" else null })
    private val noeud = NoeudPc(DatvPc(stockageNoeud, dossier, lecteursLocaux = false), stockageNoeud, 0, nom = "essai", sdr = sdrNoeud, tx = txNoeud)
        .also { it.demarre() }
    private val cle: String by lazy { JSONObject(lit("/noeud/entrer?code=" + noeud.code).second).getString("cle") }

    @After fun fin() { fini = true; noeud.arrete(); portee.cancel(); dossier.deleteRecursively() }

    private fun lit(chemin: String, post: Boolean = false, port: Int = noeud.portReel): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port$chemin").openConnection() as HttpURLConnection
        if (post) { c.requestMethod = "POST"; c.doOutput = true; c.outputStream.close() }
        val code = c.responseCode
        return code to (if (code < 400) c.inputStream else c.errorStream)?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
    }

    private fun attends(ms: Long = 8_000, quoi: () -> Boolean) {
        val fin = System.currentTimeMillis() + ms
        while (!quoi()) { check(System.currentTimeMillis() < fin) { "trop long" }; Thread.sleep(20) }
    }

    // ------------------------------------------------------------ the SDR

    private fun qo100PcParLeNoeud(etages: Int) {
        sdrNoeud.appareil = "bladerf"
        val st = StationPc(StockageMemoire(), Catalogue(dossier, enLigne = false), PortsSeriePc(), portee)
        st.settings.manualLocator = "JN18FS"
        st.demarre()
        st.settings.sdrAudio = false
        st.qo100.poseOl(true, 9_750_000_000L)
        st.qo100.descente(10_489_700_000L)
        st.qo100.aLaCle(true)
        val sdr = SdrPc(st, SonPc(), StockageMemoire(), portee)
        sdr.recepteur = SdrPc.NOEUD
        sdr.ouvreNoeud = { SourceNoeudIq.connecte("127.0.0.1", noeud.portReel, "/noeud/sdr/flux?cle=$cle", etages) }
        assertTrue(sdr.demarre())
        assertTrue(sdr.etat.value.tuner, sdr.etat.value.tuner.contains("SatMe-Node"))
        // The PC's tuning reaches the node's board.
        attends { centreFaux == 739_700_000L }
        assertTrue(sdrNoeud.etat.actif)
        sdr.demandePanorama()
        attends { sdr.demandePanorama(); sdr.mesureBalise() != null }
        val m = sdr.mesureBalise()!!
        assertEquals(300.0, m.ecartHz, 60.0)
        assertTrue("au-dessus du bruit : " + m.rapportDb, m.rapportDb > 15f)
        assertEquals(0L, sdr.etat.value.perdus)
        sdr.arrete()
        // The PC gone: the node lets its board go.
        attends { !sdrNoeud.etat.actif && fermes.contains("sdr") }
    }

    @Test fun le_sdr_du_node_dans_la_chaine_qo100() = qo100PcParLeNoeud(0)

    @Test fun le_sdr_du_node_flux_allege_par_deux() = qo100PcParLeNoeud(1)

    @Test
    fun sans_sdr_choisi_le_node_le_dit() {
        try { SourceNoeudIq.connecte("127.0.0.1", noeud.portReel, "/noeud/sdr/flux?cle=$cle"); error("refus attendu") }
        catch (x: java.io.IOException) { assertTrue(x.message, x.message!!.contains("aucun SDR choisi")) }
        try { SourceNoeudIq.connecte("127.0.0.1", noeud.portReel, "/noeud/sdr/flux?cle=mauvaise"); error("refus attendu") }
        catch (x: java.io.IOException) { assertTrue(x.message, x.message!!.contains("clé")) }
    }

    /** A relay between the PC and the node, whose connections can be cut (the network lost). */
    private inner class Relais {
        val s = ServerSocket(0)
        val liens = CopyOnWriteArrayList<Socket>()
        @Volatile var ouvert = true
        init {
            thread(isDaemon = true) {
                while (!fini) {
                    val a = runCatching { s.accept() }.getOrNull() ?: break
                    if (!ouvert) { a.close(); continue }
                    val b = Socket().apply { connect(InetSocketAddress("127.0.0.1", noeud.portReel)) }
                    liens += a; liens += b
                    thread(isDaemon = true) { runCatching { a.getInputStream().copyTo(b.getOutputStream()) }; runCatching { b.close() } }
                    thread(isDaemon = true) { runCatching { b.getInputStream().copyTo(a.getOutputStream()) }; runCatching { a.close() } }
                }
            }
        }
        fun coupe() { liens.forEach { runCatching { it.close() } }; liens.clear() }
    }

    @Test
    fun liaison_perdue_puis_reprise() {
        sdrNoeud.appareil = "pluto"
        val r = Relais()
        val src = SourceNoeudIq.connecte("127.0.0.1", r.s.localPort, "/noeud/sdr/flux?cle=$cle", 0, 739_700_000L, null, 0, reprisesMs = 1_000L)
        val buf = ShortArray(16_384)
        attends { src.read16(buf, 200) > 0 }
        assertEquals("", src.avertissement)
        // The network gone a moment: no error for the reception, a word on the page, then the stream again.
        r.ouvert = false
        r.coupe()
        attends { src.avertissement.isNotEmpty() }
        assertNull(src.erreur)
        assertTrue(src.avertissement, src.avertissement.contains("reconnexion") || src.avertissement.contains("injoignable"))
        r.ouvert = true
        attends(10_000) { src.reprises >= 1 }
        attends { src.read16(buf, 200) > 0 && src.avertissement.isEmpty() }
        // Taken up with the settings asked: the node's board on the same centre.
        assertEquals(739_700_000L, centreFaux)
        src.close()
        r.s.close()
    }

    @Test
    fun un_client_muet_rend_le_sdr() {
        sdrNoeud.appareil = "bladerf"
        // Connected, then silent (no « still there »): the node lets its board go.
        val s = Socket("127.0.0.1", noeud.portReel)
        s.getOutputStream().write("GET /noeud/sdr/flux?cle=$cle HTTP/1.1\r\n\r\n".toByteArray())
        attends { sdrNoeud.etat.actif }
        attends(5_000) { !sdrNoeud.etat.actif }
        assertTrue(fermes.contains("sdr"))
        s.close()
    }

    // ------------------------------------------------------------ the transmitter

    private fun emetteur(gainMax: Double = -10.0) = EmetteurNoeud("127.0.0.1", noeud.portReel, "/noeud/tx/flux?cle=$cle", gainMax)

    private fun reglage(hz: Long = 2_409_750_000L, appareil: String = "pluto", garde: () -> String? = { null }) =
        EmetteurNoeud.Reglage(appareil, hz, 2_333_331L, -20.0, 60_000L, paires = 4096, garde = garde)

    @Test
    fun emission_non_autorisee_par_defaut() {
        assertFalse(txNoeud.autorisee)
        val e = emetteur()
        val r = e.demarre(reglage()) { it.fill(1) }
        assertNotNull(r)
        assertTrue(r, r!!.contains("non autorisée"))
        assertEquals(0, fauxTx.demarre)
        // Only from the node itself: over the network the setting is refused.
        assertEquals(NoeudPc.SUR_PLACE, txNoeud.regleLocal(false, true, "pluto", null))
        assertFalse(txNoeud.autorisee)
        assertEquals(0, fauxTx.demarre)
    }

    private fun autorise(app: String = "pluto") { assertNull(txNoeud.regleLocal(true, true, app, null)) }

    @Test
    fun la_page_du_node_ne_change_rien_de_loin() {
        // The test runs on the loopback: the route answers; its refusal for another address is the same check (isLoopbackAddress).
        val (code, corps) = lit("/noeud/reglages?autorise=1&tx=pluto", post = true)
        assertEquals(200, code); assertTrue(corps, JSONObject(corps).getBoolean("ok"))
        assertTrue(txNoeud.autorisee)
        // Turned off from the page: refused at once afterwards.
        lit("/noeud/reglages?autorise=0", post = true)
        assertFalse(txNoeud.autorisee)
        val api = JSONObject(lit("/noeud/api").second)
        assertTrue(api.getJSONArray("services").toString().contains("tx"))
        assertFalse(api.getJSONObject("appareils").getBoolean("txAutorise"))
    }

    @Test
    fun emission_puis_arret_par_le_pc() {
        autorise()
        val e = emetteur()
        assertNull(e.demarre(reglage()) { it.fill(7) })
        attends { fauxTx.recus.get() > 50_000 }
        assertTrue(txNoeud.etat.emet)
        // The node's own ceiling: −20 dB asked, −20 kept (below −10); and never above it.
        assertEquals(-20.0, fauxTx.etat.gainDb, 0.01)
        assertEquals(-10.0, e.regleGain(0.0)!!, 0.01)
        e.arrete()
        attends(3_000) { !fauxTx.etat.emet }
        attends { !txNoeud.etat.emet }
    }

    @Test
    fun la_garde_du_pc_arrete_aussi() {
        autorise()
        val pageLa = java.util.concurrent.atomic.AtomicBoolean(true)
        val e = emetteur()
        assertNull(e.demarre(reglage { if (pageLa.get()) null else "page SatMe fermée ou injoignable" }) { it.fill(3) })
        attends { fauxTx.recus.get() > 10_000 }
        pageLa.set(false)
        attends(2_000) { !fauxTx.etat.emet }
        assertEquals("page SatMe fermée ou injoignable", e.etatCommun().raison)
    }

    @Test
    fun le_node_refuse_un_autre_emetteur_et_hors_bande() {
        autorise("bladerf")
        assertTrue(emetteur().demarre(reglage()) { }!!.contains("bladeRF"))
        assertTrue(emetteur().demarre(reglage(hz = 100_000_000L, appareil = "bladerf")) { }!!.contains("n'émet que"))
        assertEquals(0, fauxTx.demarre)
    }

    /** A PC that starts a transmission by hand, sends a little, then [apres]. */
    private fun pcBrut(apres: (Socket, FluxNoeud.Ecrivain) -> Unit) {
        val s = Socket("127.0.0.1", noeud.portReel)
        s.getOutputStream().write(("POST /noeud/tx/flux?cle=$cle&appareil=pluto&hz=2409750000&debit=2333331&gain=-20&duree=60000&paires=4096 HTTP/1.1\r\n" +
            "Content-Length: 0\r\n\r\n").toByteArray())
        val i = s.getInputStream()
        val tete = StringBuilder()
        while (!tete.endsWith("\r\n\r\n")) tete.append(i.read().toChar())
        assertTrue(tete.toString(), tete.startsWith("HTTP/1.1 200"))
        FluxNoeud.litPreambule(i)
        val e = FluxNoeud.Ecrivain(s.getOutputStream())
        repeat(5) { e.iq(ShortArray(8192) { 5 }, 8192, 2_409_750_000L, 2_333_331) }
        attends { fauxTx.etat.emet && fauxTx.recus.get() > 0 }
        apres(s, e)
    }

    @Test
    fun le_chien_de_garde_coupe_en_moins_de_2_s() {
        autorise()
        var t0 = 0L
        pcBrut { s, _ ->
            // Silent from now on, the connection left open.
            t0 = System.currentTimeMillis()
            attends(2_500) { !fauxTx.etat.emet }
            val d = System.currentTimeMillis() - t0
            assertTrue("arrêté après $d ms", d < 2_000)
            assertTrue(fauxTx.etat.raison, fauxTx.etat.raison.contains("plus rien"))
            s.close()
        }
    }

    @Test
    fun la_vie_seule_garde_l_emission_puis_la_deconnexion_l_arrete() {
        autorise()
        pcBrut { s, e ->
            // « Still there » only for 2.5 s: still on.
            val fin = System.currentTimeMillis() + 2_500
            while (System.currentTimeMillis() < fin) { e.vie(); Thread.sleep(200) }
            assertTrue(fauxTx.etat.emet)
            s.close()
            attends(1_500) { !fauxTx.etat.emet }
            assertTrue(fauxTx.etat.raison, fauxTx.etat.raison.contains("déconnecté") || fauxTx.etat.raison.contains("arrêté"))
        }
    }

    @Test
    fun desautoriser_sur_le_node_coupe_aussitot() {
        autorise()
        val e = emetteur()
        assertNull(e.demarre(reglage()) { it.fill(2) })
        attends { fauxTx.recus.get() > 10_000 }
        assertNull(txNoeud.regleLocal(true, false, null, null))
        attends(1_000) { !fauxTx.etat.emet }
        attends(2_000) { !e.etatCommun().emet }
    }

    @Test
    fun la_sante_du_node_et_ses_actions() {
        // A Pi of a few files: hot, throttled, the bladeRF on USB 2.
        val r = File(dossier, "racine")
        File(r, "proc").mkdirs(); File(r, "sys/class/thermal/thermal_zone0").mkdirs()
        File(r, "proc/uptime").writeText("7200.5 100\n"); File(r, "proc/loadavg").writeText("1.20 0.80 0.50 2/300 99\n")
        File(r, "sys/class/thermal/thermal_zone0/temp").writeText("82000\n")
        File(r, "proc/meminfo").writeText("MemTotal: 4000000 kB\nMemAvailable: 3000000 kB\n")
        val u = File(r, "sys/bus/usb/devices/2-1").apply { mkdirs() }
        File(u, "idVendor").writeText("2cf0\n"); File(u, "idProduct").writeText("5250\n"); File(u, "speed").writeText("480\n")
        val relances = java.util.concurrent.atomic.AtomicInteger()
        val datv = DatvPc(stockageNoeud, dossier, lecteursLocaux = false)
        val n = NoeudPc(datv, stockageNoeud, 0, nom = "sante", sdr = sdrNoeud, tx = txNoeud,
            sante = SanteNoeudPc(datv, sdrNoeud, txNoeud, "essai", racine = r, vcgencmd = { "throttled=0x4\n" }),
            relancable = { true }, quitte = { relances.incrementAndGet() }).also { it.demarre() }
        try {
            val k = JSONObject(lit("/noeud/entrer?code=" + n.code, port = n.portReel).second).getString("cle")
            assertEquals(403, lit("/noeud/sante", port = n.portReel).first)
            val s = JSONObject(lit("/noeud/sante?cle=$k", port = n.portReel).second)
            assertEquals("essai", s.getString("version"))
            assertEquals(82.0, s.getJSONObject("systeme").getDouble("temperature"), 0.01)
            assertEquals("USB 2", s.getJSONArray("usb").getJSONObject(0).getString("vitesse"))
            assertEquals("arret", s.getJSONObject("services").getJSONObject("tx").getString("etat"))
            val a = s.getJSONArray("alertes").toString()
            assertTrue(a, a.contains("surchauffe") && a.contains("bridé") && a.contains("USB 3"))
            // Actions: by POST with the key; a service stopped, SatMe started again (by systemd: here a counter).
            assertEquals(405, lit("/noeud/action?cle=$k&quoi=relance", port = n.portReel).first)
            assertTrue(JSONObject(lit("/noeud/action?cle=$k&quoi=sdr-arrete", post = true, port = n.portReel).second).getBoolean("ok"))
            assertFalse(JSONObject(lit("/noeud/action?cle=$k&quoi=reboot", post = true, port = n.portReel).second).getBoolean("ok"))
            assertTrue(JSONObject(lit("/noeud/action?cle=$k&quoi=relance", post = true, port = n.portReel).second).getBoolean("ok"))
            attends(3_000) { relances.get() == 1 }
        } finally { n.arrete() }
        // Started by hand (no systemd): nothing to start it again, refused.
        val m = NoeudPc(datv, stockageNoeud, 0, sdr = sdrNoeud, relancable = { false }, quitte = { relances.incrementAndGet() })
        assertFalse(m.action("relance").getBoolean("ok"))
        assertEquals(1, relances.get())
    }

    @Test
    fun le_sdr_et_l_emetteur_ne_prennent_pas_la_meme_carte() {
        autorise()
        sdrNoeud.appareil = "pluto"
        val src = SourceNoeudIq.connecte("127.0.0.1", noeud.portReel, "/noeud/sdr/flux?cle=$cle", 0, 2_400_000_000L)
        attends { sdrNoeud.etat.actif }
        assertTrue(emetteur().demarre(reglage()) { }!!.contains("occupé"))
        src.close()
    }
}
