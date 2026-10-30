/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import com.sun.net.httpserver.HttpServer
import fr.f4ioz.satcombo.datv.DepartDatv
import fr.f4ioz.satcombo.datv.SortiePtt
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlinx.coroutines.cancel
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The DATV transmission's aids: the level from 0, the station identified,
 * the page's heartbeat, the PTT output's order, the sound's delay, the
 * folder of videos and the scrolling text. Always with the simulated
 * transmitter: no device is ever opened.
 */
class AidesEmissionDatvPcTest {

    /** Null packets at a steady pace, as ffmpeg would give them. */
    private class TsVide : EmissionDatvPc.FabriqueTs {
        var parametres: EmissionDatvPc.ParametresTs? = null
        override fun demarre(p: EmissionDatvPc.ParametresTs): EmissionDatvPc.Processus {
            parametres = p
            val vivant = AtomicBoolean(true)
            val e = object : InputStream() {
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, o: Int, n: Int): Int {
                    if (!vivant.get()) return -1
                    Thread.sleep(20)
                    val k = minOf(n, 188 * 8) / 188 * 188
                    for (i in 0 until k step 188) { b[o + i] = 0x47; b[o + i + 1] = 0x1F; b[o + i + 2] = 0xFF.toByte(); b[o + i + 3] = 0x10 }
                    return k
                }
            }
            return object : EmissionDatvPc.Processus {
                override val sortie: InputStream = e
                override fun arrete() { vivant.set(false) }
                override val vivant: Boolean get() = vivant.get()
            }
        }
    }

    /** A relay that notes when it moved (ms since the test's start). */
    private class Relais : SortiePtt {
        val t0 = System.nanoTime()
        val journal = java.util.Collections.synchronizedList(ArrayList<Pair<Boolean, Long>>())
        override val nom = "relais d'essai"
        override fun pose(actif: Boolean): String? { journal += actif to (System.nanoTime() - t0) / 1_000_000; return null }
        fun ms() = (System.nanoTime() - t0) / 1_000_000
    }

    private fun attends(ms: Long, c: () -> Boolean): Boolean {
        val fin = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fin) { if (c()) return true; Thread.sleep(20) }
        return c()
    }

    private fun emission(locator: String = "JN18du", indicatif: String = "F4IOZ", vieMs: Long = 15_000L, ptt: (StockageMemoire) -> PttDatvPc = { PttDatvPc(it) },
                         ts: EmissionDatvPc.FabriqueTs = TsVide()): EmissionDatvPc {
        val st = StockageMemoire()
        return EmissionDatvPc(st, File(System.getProperty("java.io.tmpdir"), "satme-test-datv-aides"), { indicatif }, { locator },
            plutoOccupe = { "aucun Pluto ne doit être ouvert" }, fabrique = ts, vieMs = vieMs, ptt = ptt(st)).apply {
            simulation = true; frequenceKhz = 2_409_750L; srKs = 333; dureeS = 60
        }
    }

    @Test
    fun niveau_a_zero_au_depart_et_apres_chaque_arret() {
        val e = emission()
        assertTrue(e.niveauAZero)
        assertEquals(DepartDatv.NIVEAU_ZERO, e.demarre(true, chargeFictive = true))
        assertFalse(e.etat.emet)
        // The operator's gesture on the level: then it starts (simulated, nothing opened).
        e.regleGain(-45.0)
        assertFalse(e.niveauAZero)
        assertNull(e.demarre(true, chargeFictive = true))
        assertTrue(e.etat.emet && e.etat.simulation)
        assertEquals("SIMULATION", if (e.json().getBoolean("simulationEnCours")) "SIMULATION" else "")
        e.arrete()
        // Back to 0: the next start needs a gesture again.
        assertTrue(e.niveauAZero)
        assertEquals(DepartDatv.NIVEAU_ZERO, e.demarre(true, chargeFictive = true))
        assertTrue(e.json().getString("refusDepart").startsWith("niveau à 0"))
        // « Garder le niveau » checked: kept after the stop.
        e.garderNiveau = true; e.regleGain(-45.0)
        assertNull(e.demarre(true, chargeFictive = true))
        e.arrete()
        assertFalse(e.niveauAZero)
        e.niveauZero()
        assertTrue(e.niveauAZero)
        // A stop with nothing running does not touch it.
        e.regleGain(-45.0); e.garderNiveau = false; e.arrete()
        assertFalse(e.niveauAZero)
    }

    @Test
    fun indicatif_et_locator_avant_tout() {
        val sans = emission(locator = "")
        sans.regleGain(-45.0)
        assertTrue(sans.demarre(true, chargeFictive = true)!!.startsWith("locator manquant"))
        val nocall = emission(indicatif = "NOCALL")
        nocall.regleGain(-45.0)
        assertTrue(nocall.demarre(true, chargeFictive = true)!!.contains("un exemple"))
        assertFalse(sans.etat.emet || nocall.etat.emet)
        assertTrue(sans.json().getString("refusIdentification").startsWith("locator manquant"))
    }

    @Test
    fun page_muette_coupe_l_emission_puis_le_relais() {
        val r = Relais()
        val e = emission(vieMs = 400L, ptt = { st -> PttDatvPc(st, fabrique = { r }).also {
            assertNull(it.regle(mapOf("type" to "http", "urlActive" to "http://192.0.2.1/on", "urlRepos" to "http://192.0.2.1/off", "avant" to "250", "apres" to "300"), false))
        } })
        e.regleGain(-45.0)
        val avant = r.ms()
        assertNull(e.demarre(true, chargeFictive = true))
        val parti = r.ms()
        // The relay first, then the delay before the RF: the start returns once it is waited.
        assertEquals(true, r.journal.first().first)
        assertTrue("délai avant ${parti - r.journal.first().second} ms", parti - r.journal.first().second >= 240 && r.journal.first().second >= avant)
        assertTrue(e.json().getJSONObject("ptt").getBoolean("actif"))
        // No heartbeat: the transmitter stops by itself (« page fermée »), SatMe notices, the relay goes at rest after its delay.
        assertTrue(attends(5000) { e.tic(); !e.etat.emet })
        val coupe = r.ms()
        assertTrue(e.etat.raison, e.etat.raison.contains("page SatMe fermée"))
        assertTrue(attends(3000) { r.journal.size >= 2 })
        val (actif, quand) = r.journal[1]
        assertFalse(actif)
        assertTrue("délai après ${quand - coupe} ms", quand - coupe >= 250)
        assertTrue(e.niveauAZero)
    }

    @Test
    fun la_page_qui_bat_garde_l_emission() {
        val e = emission(vieMs = 400L)
        e.regleGain(-45.0)
        assertNull(e.demarre(true, chargeFictive = true))
        val fin = System.currentTimeMillis() + 1500
        while (System.currentTimeMillis() < fin) { e.vie(); e.tic(); Thread.sleep(100) }
        assertTrue(e.etat.emet)
        e.arrete()
        assertFalse(e.etat.emet)
    }

    @Test
    fun sortie_ptt_refusee_aucune_emission() {
        val refuse = object : SortiePtt {
            var poses = ArrayList<Boolean>()
            override val nom = "boîtier"
            override fun pose(actif: Boolean): String? { poses += actif; return if (actif) "injoignable" else null }
        }
        val ts = TsVide()
        val e = emission(ptt = { st -> PttDatvPc(st, fabrique = { refuse }).also {
            it.regle(mapOf("type" to "http", "urlActive" to "http://192.0.2.1/on", "urlRepos" to "http://192.0.2.1/off"), false) } }, ts = ts)
        e.regleGain(-45.0)
        val r = e.demarre(true, chargeFictive = true)
        assertTrue(r!!.contains("sortie PTT") && r.contains("injoignable"))
        assertFalse(e.etat.emet)
        assertEquals(listOf(true, false), refuse.poses)
    }

    @Test
    fun reglages_de_la_sortie_ptt() {
        val p = PttDatvPc(StockageMemoire())
        assertEquals("aucune", p.reglage.type.cle)
        assertTrue(p.teste(false)!!.contains("aucune"))
        assertNotNull(p.regle(mapOf("type" to "serie"), false))
        assertNotNull(p.regle(mapOf("type" to "http", "urlActive" to "ftp://x", "urlRepos" to "http://x"), false))
        assertNotNull(p.regle(mapOf("type" to "http", "urlActive" to "http://x"), false))
        assertNull(p.regle(mapOf("type" to "serie", "port" to "/dev/ttyUSB9", "ligne" to "dtr", "avant" to "99999", "apres" to "-3"), false))
        assertEquals(5_000L, p.reglage.avantMs); assertEquals(0L, p.reglage.apresMs); assertEquals("dtr", p.reglage.ligne)
        // Never changed while transmitting.
        assertNotNull(p.regle(mapOf("type" to "aucune"), emet = true))
        assertEquals("serie", p.json(false).getString("type"))
    }

    @Test
    fun crochet_http_vers_un_faux_boitier() {
        val vus = java.util.Collections.synchronizedList(ArrayList<String>())
        var code = 200
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/") { x -> vus += x.requestURI.toString(); x.sendResponseHeaders(code, -1); x.close() }
        srv.start()
        try {
            val base = "http://127.0.0.1:" + srv.address.port
            val s = SortiePttHttp("$base/ptt?on=1", "$base/ptt?on=0", 1000)
            assertNull(s.pose(true)); assertNull(s.pose(false))
            assertEquals(listOf("/ptt?on=1", "/ptt?on=0"), vus)
            code = 500
            assertEquals("réponse 500", s.pose(true))
        } finally { srv.stop(0) }
        assertTrue(SortiePttHttp("http://127.0.0.1:9/x", "http://127.0.0.1:9/y", 500).pose(true)!!.startsWith("injoignable"))
    }

    @Test
    fun decalage_du_son_borne_et_dans_la_commande() {
        assertEquals(500, EmissionDatvPc.decalageSon(900)); assertEquals(-500, EmissionDatvPc.decalageSon(-501)); assertEquals(120, EmissionDatvPc.decalageSon(120))
        val e = emission()
        e.decalageSonMs = 2_000
        assertEquals(500, e.decalageSonMs)
        e.decalageSonMs = -80
        assertEquals("atrim=start=0.080,asetpts=PTS-STARTPTS", EmissionDatvPc.filtreSon(e.decalageSonMs))
        assertEquals("adelay=delays=250:all=1", EmissionDatvPc.filtreSon(250))
        assertNull(EmissionDatvPc.filtreSon(0))
        val p = EmissionDatvPc.ParametresTs(400_000L, "mire", File("/m.png"), "", "F4IOZ", false, true, decalageSonMs = 250)
        val c = EmissionDatvPc.commande("ffmpeg", p)
        val i = c.indexOf("-af")
        assertTrue(i > 0 && c[i + 1] == "adelay=delays=250:all=1" && i < c.indexOf("-c:a"))
        // No sound, no filter; the preview leaves it out with the coding.
        assertFalse("-af" in EmissionDatvPc.commande("ffmpeg", p.copy(son = false)))
        assertFalse("-af" in IncrustationDatvPc.commandeApercu(c, File("/a.jpg")))
        // Taken at the start: in what the stream's maker gets.
        val ts = TsVide()
        val e2 = emission(ts = ts)
        e2.decalageSonMs = -300; e2.regleGain(-45.0)
        assertNull(e2.demarre(true, chargeFictive = true))
        assertEquals(-300, ts.parametres!!.decalageSonMs)
        e2.arrete()
    }

    @Test
    fun dossier_de_videos_en_boucle() {
        val d = kotlin.io.path.createTempDirectory("satme-dossier").toFile()
        try {
            listOf("b.mp4", "A.mkv", ".cache.mp4", "notes.txt", "l'été.mov").forEach { File(d, it).writeText("x") }
            val l = EmissionDatvPc.videosDe(d)
            assertEquals(listOf("A.mkv", "b.mp4", "l'été.mov"), l.map { it.name })
            val txt = EmissionDatvPc.listeConcat(l)
            assertTrue(txt.startsWith("ffconcat version 1.0\n"))
            assertTrue(txt, txt.contains("file '" + File(d, "l").path + "'\\''été.mov'"))
            val c = EmissionDatvPc.commande("ffmpeg", EmissionDatvPc.ParametresTs(400_000L, "dossier", File(d, "liste.txt"), "", "F4IOZ", false, true))
            val k = c.indexOf("concat")
            assertTrue(k > 0 && c[k - 1] == "-f" && "-stream_loop" in c.subList(0, k))
            // The files' own sound, no lavfi tone.
            assertTrue("0:a:0?" in c && "lavfi" !in c)
            // Refused when the folder holds no video; started otherwise (simulated).
            val e = emission()
            e.source = "dossier"; e.dossierVideos = File(d, "vide").path; e.regleGain(-45.0)
            assertTrue(e.demarre(true, chargeFictive = true)!!.startsWith("dossier introuvable"))
            File(d, "vide").mkdirs()
            assertTrue(e.demarre(true, chargeFictive = true)!!.startsWith("aucune vidéo"))
            e.dossierVideos = d.path
            assertNull(e.demarre(true, chargeFictive = true))
            e.arrete()
        } finally { d.deleteRecursively() }
    }

    @Test
    fun texte_defilant_par_ffmpeg() {
        val st = StockageMemoire()
        val inc = IncrustationDatvPc(st) { 0L }
        inc.regle(mapOf("texte" to "CQ QO-100 de F4IOZ", "defile" to "1", "heure" to "1"))
        assertTrue(inc.reglage.defile)
        val dir = kotlin.io.path.createTempDirectory("satme-defile").toFile()
        try {
            val o = inc.pour(429_832L, "image", "F4IOZ", "JN18", File(dir, "inc.png"))!!
            val d = o.defile!!
            assertTrue(d.png.isFile && d.vitessePx > 0)
            val e = IncrustationDatvPc.entree(o)
            assertEquals(2, e.count { it == "-i" })
            assertEquals(d.png.path, e.last())
            val f = IncrustationDatvPc.filtre("[0:v]x", 2, 640, 360, d)
            assertTrue(f, f.contains("[3:v]format=rgba[d]") && f.contains("overlay=x='W-mod(t*") && f.endsWith("[v]"))
            // The box at the bottom left: the text runs along the top.
            assertTrue(d.y < 360 / 2)
            // Off: no second input, the box as before.
            inc.regle(mapOf("defile" to "0"))
            assertNull(inc.pour(429_832L, "image", "F4IOZ", "JN18", File(dir, "inc.png"))!!.defile)
        } finally { dir.deleteRecursively() }
    }

    /** With ffmpeg here: 3 s of a folder of two videos, the scrolling text over it and the sound 200 ms later. */
    @Test
    fun ffmpeg_dossier_defilement_et_decalage() {
        val ff = listOf("/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg").firstOrNull { File(it).canExecute() }
        assumeTrue(ff != null && File("/usr/bin/ffprobe").canExecute())
        val dir = kotlin.io.path.createTempDirectory("satme-dossier-ff").toFile()
        try {
            for ((n, c) in listOf("1.mp4" to "red", "2.mp4" to "green")) run(listOf(ff!!, "-v", "error", "-f", "lavfi", "-i", "color=c=$c:s=320x240:d=1:r=25",
                "-f", "lavfi", "-i", "sine=frequency=440:d=1", "-c:v", "libx264", "-c:a", "aac", "-shortest", File(dir, n).path))
            val liste = File(dir, "liste.txt").also { it.writeText(EmissionDatvPc.listeConcat(EmissionDatvPc.videosDe(dir))) }
            val inc = IncrustationDatvPc(StockageMemoire())
            inc.regle(mapOf("texte" to "Essai défilant SatMe", "defile" to "1"))
            val ts = 429_832L
            for ((source, decalage) in listOf("dossier" to 200, "mire" to -150)) {
                val img = if (source == "dossier") liste else File(dir, "mire.png").also {
                    run(listOf(ff!!, "-v", "error", "-f", "lavfi", "-i", "color=c=blue:s=640x360", "-frames:v", "1", "-y", it.path)) }
                val o = inc.pour(ts, source, "F4IOZ", "JN18", File(dir, "inc.png"), direct = false)
                val out = File(dir, "flux-$source.ts")
                val c = EmissionDatvPc.commande(ff!!, EmissionDatvPc.ParametresTs(ts, source, img, "", "F4IOZ", false, true, o, decalageSonMs = decalage))
                val p = ProcessBuilder(c.dropLast(1) + listOf("-t", "3", "-y", out.path)).redirectError(File(dir, "err.txt")).start()
                p.outputStream.close()
                assertTrue("ffmpeg : " + File(dir, "err.txt").let { if (it.isFile) it.readText() else "" }, p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0)
                val probe = ProcessBuilder("/usr/bin/ffprobe", "-v", "error", "-show_entries", "stream=codec_type,codec_name", "-of", "csv=p=0", out.path)
                    .redirectErrorStream(true).start()
                val vu = probe.inputStream.bufferedReader().readText()
                assertTrue(vu, "h264,video" in vu && "aac,audio" in vu)
                val kbps = out.length() * 8 / 3.0
                assertTrue("débit $kbps", kbps in ts * 0.85..ts * 1.15)
            }
        } finally { dir.deleteRecursively() }
    }

    /**
     * Through SatMe-PC's routes, from this PC: tab A starts, tab B watches.
     * B's heartbeats do not keep the transmission; A falling silent stops it
     * (the RF, then the relay); a click on the spectrum sets the uplink with
     * its hint; the PTT settings refused from another machine.
     */
    @Test
    fun deux_onglets_un_seul_pilote_par_les_routes() {
        val dossier = kotlin.io.path.createTempDirectory("satme-aides-serveur").toFile()
        val portee = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val r = Relais()
        val e = emission(vieMs = 600L, ptt = { st -> PttDatvPc(st, fabrique = { r }) })
        val st = StationPc(StockageMemoire(), Catalogue(dossier, enLigne = false), PortsSeriePc(), portee)
        val s = ServeurPc(st, 0, emission = e)
        s.demarre()
        try {
            val base = "http://127.0.0.1:" + s.portReel
            fun req(chemin: String, post: Boolean = true): org.json.JSONObject {
                val c = java.net.URL(base + chemin).openConnection() as java.net.HttpURLConnection
                if (post) { c.requestMethod = "POST"; c.doOutput = true; c.outputStream.close() }
                return org.json.JSONObject(c.inputStream.readBytes().toString(Charsets.UTF_8))
            }
            // The level first: refused at 0, set by the operator's gesture.
            assertTrue(req("/api/datv/emission?client=A", post = false).getBoolean("niveauZero"))
            assertFalse(req("/api/datv/emission/demarre?confirme=1&charge=1&client=A").getBoolean("ok"))
            req("/api/datv/emission/reglage?gain=-45")
            assertTrue(req("/api/datv/emission/demarre?confirme=1&charge=1&client=A").optBoolean("ok", true))
            assertTrue(e.etat.emet)
            assertTrue(req("/api/datv/emission?client=A", post = false).getJSONObject("controle").getBoolean("moi"))
            assertFalse(req("/api/datv/emission?client=B", post = false).getJSONObject("controle").getBoolean("moi"))
            // Tab B beats, tab A is silent: stopped all the same.
            val fin = System.currentTimeMillis() + 4_000
            while (e.etat.emet && System.currentTimeMillis() < fin) { assertFalse(req("/api/datv/emission/vie?client=B").getBoolean("ok")); e.tic(); Thread.sleep(100) }
            assertFalse(e.etat.emet)
            assertTrue(attends(3000) { r.journal.size >= 2 && !r.journal.last().first })
            // The level back to 0: « Démarrer » greyed again, its reason given.
            assertTrue(req("/api/datv/emission?client=A", post = false).getString("refusDepart").startsWith("niveau à 0"))
            // A click on the spectrum near 10 499,30 MHz: the 10 499,25 channel, its hint (indicative).
            val c = req("/api/datv/emission/reglage?clicDescenteKhz=10499300")
            assertEquals(2_409_750L, c.getLong("khz"))
            assertTrue(c.getJSONObject("indice").getString("texte").isNotEmpty())
            assertEquals(2_409_750L, e.frequenceKhz)
            // The PTT output's settings: this PC only.
            assertEquals("aucune", req("/api/datv/emission/ptt", post = false).getString("type"))
            s.estLocal = { false }
            val ici = java.net.URL("$base/api/datv/emission/ptt?type=http").openConnection() as java.net.HttpURLConnection
            ici.requestMethod = "POST"; ici.doOutput = true; ici.outputStream.close()
            assertTrue(ici.responseCode != 200 || !org.json.JSONObject(ici.inputStream.readBytes().toString(Charsets.UTF_8)).optBoolean("ok", true))
            assertEquals("aucune", e.ptt.reglage.type.cle)
        } finally { s.arrete(); e.arrete(); portee.cancel(); dossier.deleteRecursively() }
    }

    private fun run(c: List<String>) {
        val p = ProcessBuilder(c).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        check(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) { c.joinToString(" ") }
    }
}
