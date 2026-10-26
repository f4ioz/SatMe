/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.sstv.ReceptionSim
import fr.f4ioz.satcombo.sstv.SstvConditions
import fr.f4ioz.satcombo.sstv.SstvEncoder
import fr.f4ioz.satcombo.sstv.SstvMode
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import java.io.File
import java.util.Properties

/**
 * The demonstration instance: `satme --demo [--dossier DIR] [--demo-reset]`.
 *
 * A made-up station ([GenerateurDemo]) in a data folder of its own
 * (`~/.satme-demo`, never the station's: a folder holding other data is
 * refused), virtual hardware plugged in at start ([branche]): the simulated
 * IC-9700, the simulated mast, the SDR's test generator, trial aircraft and
 * ships, an SSTV test card as the sound card ([SonDemo]).
 *
 * **Nothing real is ever opened** while [actif]: no serial port, no sound
 * card in or out, no SDR, no transmitter, no MiniTiouner, no SatMe-Node, no
 * upload under the made-up callsign; each place says so through [refus].
 * The remote access keeps its password (it cannot be removed). A band on
 * every page says « DÉMO — données fictives ».
 */
object ModeDemo {

    @Volatile var actif = false; private set

    /** From now on, this instance is the demonstration (before anything opens). */
    fun active() {
        actif = true
        System.setProperty("satme.sansSerie", "true")
    }

    /** For the tests only. */
    internal fun desactive() {
        actif = false
        System.clearProperty("satme.sansSerie")
    }

    /** Why [quoi] is not done (the demonstration), or null when it may be. */
    fun refus(quoi: String): String? = if (actif) "$quoi : désactivé dans la démonstration (matériel virtuel seulement)" else null

    const val MARQUE = ".satme-demo"
    /** Asked from the page (« Remettre à neuf »): the next start begins from nothing. */
    const val DEMANDE_RESET = ".satme-demo-reset"
    /** The dated data are made again when older than this (the passes stay those of the last days). */
    const val VALIDITE_MS = 2 * 86_400_000L
    /**
     * The hook for the polarisation switch (ESP32): its port, and the switch on
     * each band. The demonstration writes a virtual one there (WiMo on UHF,
     * none on VHF); read once the polarisation switch is in SatMe-PC.
     */
    const val CLE_POLARISATION = "pc_pol_port"
    const val POLARISATION_VIRTUELLE = "virtuel"
    const val CLE_POLARISATION_UHF = "pc_pol_uhf"
    const val CLE_POLARISATION_VHF = "pc_pol_vhf"
    /** Kept through a reset: the remote access password, its sessions, the HTTPS certificate. */
    val GARDES = setOf("acces-distant.properties", "acces-sessions.txt", "https")

    fun dossierParDefaut(maison: String = System.getProperty("user.home")): File = File(maison, ".satme-demo")

    /** Why [dossier] cannot hold the demonstration (another station's data are there), or null. */
    fun refusDossier(dossier: File): String? {
        if (File(dossier, MARQUE).isFile) return null
        if (dossier.exists() && !dossier.isDirectory) return "${dossier.path} n'est pas un dossier"
        val l = dossier.list().orEmpty().filter { it !in GARDES }
        return if (l.isEmpty()) null
        else "${dossier.path} contient déjà d'autres données que la démonstration : en choisir un autre (--dossier), vide ou nouveau"
    }

    /** When the data were made (ms), null when [dossier] holds none. */
    fun genere(dossier: File): Long? = runCatching {
        val p = Properties()
        File(dossier, MARQUE).reader(Charsets.UTF_8).use { p.load(it) }
        p.getProperty("genere")?.trim()?.toLongOrNull()
    }.getOrNull()

    /**
     * At start: [dossier] made into the demonstration when new, begun again
     * with [reset] (or when asked from the page), its dated part made again
     * when too old. A line for the log; throws when [dossier] is not one.
     */
    fun prepare(dossier: File, reset: Boolean, maintenant: Long = System.currentTimeMillis()): String {
        refusDossier(dossier)?.let { error(it) }
        val demande = File(dossier, DEMANDE_RESET).isFile
        if (reset || demande) efface(dossier)
        val g = genere(dossier)
        return when {
            g == null -> GenerateurDemo(maintenant).genere(dossier, reglages = true).let {
                "  démonstration : données fictives créées (${it.passages} passages, ${it.contacts} contacts, ${it.images} images SSTV)"
            }
            maintenant - g > VALIDITE_MS || g > maintenant + 60_000L -> GenerateurDemo(maintenant).genere(dossier, reglages = false).let {
                "  démonstration : passages, carnet et images refaits aux dates d'aujourd'hui"
            }
            else -> "  démonstration : données fictives du " + java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.FRANCE).format(java.util.Date(g))
        }
    }

    /** Everything of the demonstration removed from [dossier], [GARDES] kept; nothing done to a folder that is not one. */
    fun efface(dossier: File) {
        if (!File(dossier, MARQUE).isFile && !File(dossier, DEMANDE_RESET).isFile) return
        dossier.listFiles().orEmpty().filter { it.name !in GARDES }.forEach { it.deleteRecursively() }
    }

    /** The next start begins from nothing (« Remettre à neuf » on the page). */
    fun demandeReset(dossier: File) {
        if (File(dossier, MARQUE).isFile) File(dossier, DEMANDE_RESET).writeText("1")
    }

    /** While running: the dated part made again when too old (a service up for days). True when done. */
    fun rafraichit(dossier: File, maintenant: Long = System.currentTimeMillis()): Boolean {
        val g = genere(dossier) ?: return false
        if (maintenant - g <= VALIDITE_MS) return false
        GenerateurDemo(maintenant).genere(dossier, reglages = false)
        return true
    }

    /**
     * « Remettre à neuf »: the next start from nothing, and that start now —
     * by the service (systemd starts it again), by SatMe-PC itself, or, when
     * neither can, the dated part made again here and the settings at the next start.
     */
    fun remetANeuf(dossier: File, redemarrage: RedemarragePc, env: (String) -> String? = System::getenv): org.json.JSONObject {
        if (!actif) return org.json.JSONObject().put("ok", false).put("erreur", "pas une démonstration")
        demandeReset(dossier)
        val service = !env("INVOCATION_ID").isNullOrBlank()
        return when {
            service -> {
                Thread({ Thread.sleep(800); kotlin.system.exitProcess(0) }, "satme-demo-reset").apply { isDaemon = true; start() }
                org.json.JSONObject().put("ok", true).put("redemarre", true).put("message", "La démonstration repart de zéro : la page revient dans une quinzaine de secondes.")
            }
            redemarrage.refus() == null -> {
                Thread({ Thread.sleep(800); redemarrage.lance() }, "satme-demo-reset").apply { isDaemon = true; start() }
                org.json.JSONObject().put("ok", true).put("redemarre", true).put("message", "La démonstration repart de zéro : la page revient dans une quinzaine de secondes.")
            }
            else -> {
                GenerateurDemo(System.currentTimeMillis()).genere(dossier, reglages = false)
                org.json.JSONObject().put("ok", true).put("redemarre", false)
                    .put("message", "Passages, carnet et images refaits. Les réglages seront remis à neuf au prochain démarrage.")
            }
        }
    }

    /**
     * The virtual hardware plugged in, as the station's own: the simulated
     * IC-9700 (CI-V, nothing opened), the simulated mast following, the SDR on
     * its test generator (started from its tab), trial aircraft and ships.
     */
    fun branche(station: StationPc, sdr: SdrPc, adsb: AdsbPc, ais: AisPc) {
        station.stationRotor.simulationSeule = true
        if (!station.settings.rotorSim) station.stationRotor.setRotorSim(true)
        station.stationRotor.connectRotor()
        station.connecteCat("SIM", 0, 0)
        if (sdr.recepteur != SdrPc.FICHIER) sdr.recepteur = SdrPc.GENERATEUR
        runCatching { adsb.essai() }
        runCatching { ais.essai() }
    }

    // ---------------------------------------------------------------- the band

    private const val BANDE = "<div id=\"satme-demo\" role=\"note\" style=\"position:fixed;left:54px;bottom:30px;z-index:2147483000;" +
        "display:flex;gap:8px;align-items:center;background:#b45309;color:#fff;font:600 12px/1.3 system-ui,sans-serif;" +
        "padding:4px 6px 4px 10px;border-radius:6px;box-shadow:0 2px 8px rgba(0,0,0,.35);opacity:.93\">" +
        "<span title=\"Station de démonstration : indicatif, carnet, journal et images inventés ; poste, rotor, SDR, avions et navires simulés. Rien n'émet.\">" +
        "DÉMO — données fictives</span>" +
        "<button type=\"button\" style=\"font:inherit;font-weight:500;background:rgba(255,255,255,.18);color:#fff;border:1px solid rgba(255,255,255,.5);" +
        "border-radius:4px;padding:1px 6px;cursor:pointer\" onclick=\"if(confirm('Remettre la démonstration à neuf ? Les données fictives sont refaites et SatMe-PC redémarre.'))" +
        "fetch('/api/demo/reset',{method:'POST'}).then(function(r){return r.json()}).then(function(o){alert(o.ok?(o.message||'Redémarrage…'):(o.erreur||'impossible'));" +
        "if(o.ok)setTimeout(function(){location.reload()},o.redemarre?15000:300)}).catch(function(){setTimeout(function(){location.reload()},15000)})\">" +
        "Remettre à neuf</button></div>"

    /** [html] with the band, right after its `<body>` (unchanged when not the demonstration or no body). */
    fun bandeau(html: String): String {
        if (!actif) return html
        val m = Regex("<body[^>]*>", RegexOption.IGNORE_CASE).find(html) ?: return html
        return html.substring(0, m.range.last + 1) + BANDE + html.substring(m.range.last + 1)
    }

    fun bandeau(octets: ByteArray): ByteArray = if (!actif) octets else bandeau(String(octets, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
}

/**
 * The demonstration's sound card: an SSTV picture of the made-up series now
 * and then (Robot 36, as heard through a pass: some hiss and fading), quiet
 * hiss between them. Listed and started as a card, nothing opened.
 */
class SonDemo(private val graine: Long = GenerateurDemo.GRAINE) {

    @Volatile private var fil: Thread? = null
    @Volatile private var marche = false

    fun source(): SonPc.SourceVirtuelle = SonPc.SourceVirtuelle(NOM, "Mire SSTV de démonstration (aucune carte ouverte)",
        disponible = { ModeDemo.actif }, demarre = { recoit -> demarre(recoit) }, arrete = { arrete() })

    private fun demarre(recoit: (ShortArray, Int, Int) -> Unit): String? {
        arrete()
        marche = true
        fil = Thread({ boucle(recoit) }, "satme-son-demo").apply { isDaemon = true; start() }
        return null
    }

    fun arrete() {
        marche = false
        fil?.let { runCatching { it.interrupt() } }
        fil = null
    }

    private fun boucle(recoit: (ShortArray, Int, Int) -> Unit) {
        val rnd = java.util.Random(graine)
        val buf = ShortArray(BLOC)
        val debut = System.nanoTime()
        var envoyes = 0L
        var n = 0
        fun cadence() {
            // At the pace of a real card: never ahead of the clock.
            val avance = envoyes * 1_000_000_000L / RATE - (System.nanoTime() - debut)
            if (avance > 0) Thread.sleep(avance / 1_000_000L, (avance % 1_000_000L).toInt())
        }
        try {
            while (marche) {
                // Hiss between two pictures.
                repeat(RATE * SILENCE_S / BLOC) {
                    if (!marche) return
                    for (i in buf.indices) buf[i] = (rnd.nextGaussian() * 250).toInt().toShort()
                    recoit(buf, buf.size, RATE); envoyes += buf.size; cadence()
                }
                val mode = SstvMode.byName("Robot 36") ?: return
                val diapo = 1 + n++ % GenerateurDemo.DIAPOS
                val im = ImagesDemo.image(diapo, GenerateurDemo.DIAPOS, mode.width, mode.height, graine + diapo)
                val src = SstvEncoder.Source(mode, im.getRGB(0, 0, im.width, im.height, null, 0, im.width), RATE)
                val sim = ReceptionSim(SstvConditions(snrDb = 18.0, fading = SstvConditions.Fading.LIGHT), RATE, src.totalSamples, graine + n)
                while (marche) {
                    val k = src.read(buf); if (k <= 0) break
                    sim.apply(buf, k)
                    recoit(buf, k, RATE); envoyes += k; cadence()
                }
            }
        } catch (_: InterruptedException) {}
    }

    companion object {
        const val NOM = "demo-mire-sstv"
        const val RATE = 48_000
        const val BLOC = 1920
        const val SILENCE_S = 40
    }
}

/**
 * The made-up SSTV series' pictures, drawn here (no picture of anybody's):
 * a sky, the Earth's limb, a satellite, the series' number, « DÉMO ».
 * Same arguments, same picture.
 */
object ImagesDemo {

    private val TEINTES = floatArrayOf(0.60f, 0.55f, 0.68f, 0.75f, 0.50f, 0.62f, 0.80f, 0.58f, 0.70f, 0.52f, 0.66f, 0.73f)

    private fun police(taille: Float, gras: Boolean): Font = ToileJava2dPc.police(taille, gras)

    /** Picture [diapo] of [total] at [w]×[h]; [fraction] < 1: received up to there only (the rest dark, as a lost signal). */
    fun image(diapo: Int, total: Int, w: Int, h: Int, graine: Long, fraction: Double = 1.0): BufferedImage {
        val rnd = java.util.Random(graine * 31 + diapo)
        val im = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = im.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val k = w / 320f
        val teinte = TEINTES[(diapo - 1).mod(TEINTES.size)]
        // The sky.
        g.paint = GradientPaint(0f, 0f, Color.getHSBColor(teinte, 0.85f, 0.18f), 0f, h.toFloat(), Color.getHSBColor(teinte + 0.05f, 0.7f, 0.55f))
        g.fillRect(0, 0, w, h)
        repeat(60) {
            val b = 120 + rnd.nextInt(135)
            g.color = Color(b, b, b)
            val r = if (rnd.nextInt(8) == 0) 2 else 1
            g.fillRect(rnd.nextInt(w), rnd.nextInt(h * 2 / 3), (r * k).toInt().coerceAtLeast(1), (r * k).toInt().coerceAtLeast(1))
        }
        // The Earth's limb, its blue glow.
        val rayon = w * 1.1
        val cx = w / 2.0 + (rnd.nextDouble() - 0.5) * w * 0.4
        val cy = h + rayon - h * 0.28
        g.color = Color(90, 170, 255, 90)
        g.fill(Ellipse2D.Double(cx - rayon - 6 * k, cy - rayon - 6 * k, 2 * (rayon + 6 * k), 2 * (rayon + 6 * k)))
        g.paint = GradientPaint(0f, (cy - rayon).toFloat(), Color(40, 110, 200), 0f, h.toFloat(), Color(10, 50, 90))
        g.fill(Ellipse2D.Double(cx - rayon, cy - rayon, 2 * rayon, 2 * rayon))
        g.color = Color(70, 130, 60, 160)
        repeat(5) {
            val x = cx - rayon * 0.6 + rnd.nextDouble() * rayon * 1.2
            g.fill(Ellipse2D.Double(x, cy - rayon + h * 0.04 + rnd.nextDouble() * h * 0.12, w * (0.08 + rnd.nextDouble() * 0.15), h * 0.05))
        }
        // The satellite: a body, two panels, an antenna.
        val sx = w * (0.25 + rnd.nextDouble() * 0.5); val sy = h * (0.3 + rnd.nextDouble() * 0.15)
        val angle = (rnd.nextDouble() - 0.5) * 0.6
        val tr = g.transform
        g.translate(sx, sy); g.rotate(angle)
        g.color = Color(40, 60, 140); g.fillRect((-70 * k).toInt(), (-9 * k).toInt(), (50 * k).toInt(), (18 * k).toInt())
        g.fillRect((20 * k).toInt(), (-9 * k).toInt(), (50 * k).toInt(), (18 * k).toInt())
        g.color = Color(150, 170, 210); g.stroke = BasicStroke(k)
        for (i in 0 until 5) {
            g.drawLine(((-70 + i * 10) * k).toInt(), (-9 * k).toInt(), ((-70 + i * 10) * k).toInt(), (9 * k).toInt())
            g.drawLine(((20 + i * 10) * k).toInt(), (-9 * k).toInt(), ((20 + i * 10) * k).toInt(), (9 * k).toInt())
        }
        g.color = Color(215, 190, 120); g.fillRect((-18 * k).toInt(), (-14 * k).toInt(), (36 * k).toInt(), (28 * k).toInt())
        g.color = Color(230, 230, 230); g.stroke = BasicStroke(1.5f * k)
        g.drawLine(0, (14 * k).toInt(), (10 * k).toInt(), (32 * k).toInt())
        g.transform = tr
        // The header and the footer.
        g.color = Color(0, 0, 0, 140); g.fillRect(0, 0, w, (34 * k).toInt()); g.fillRect(0, h - (26 * k).toInt(), w, (26 * k).toInt())
        g.color = Color.WHITE; g.font = police(20 * k, true)
        g.drawString("SatMe DÉMO", 10 * k, 25 * k)
        val num = "$diapo/$total"
        g.color = Color(255, 200, 80); g.drawString(num, w - 10 * k - g.fontMetrics.stringWidth(num), 25 * k)
        g.color = Color(230, 230, 230); g.font = police(11 * k, false)
        g.drawString("Image fictive · " + GenerateurDemo.INDICATIF + " · " + TITRES[(diapo - 1).mod(TITRES.size)], 10 * k, h - 9 * k)
        g.dispose()
        // As received: a little noise on every line, a lost end.
        val coupe = (h * fraction.coerceIn(0.0, 1.0)).toInt()
        for (y in 0 until h) {
            val bruit = rnd.nextGaussian() * 5
            for (x in 0 until w) {
                if (y >= coupe) { im.setRGB(x, y, (rnd.nextInt(18) * 0x010101)); continue }
                val p = im.getRGB(x, y)
                val d = (bruit + rnd.nextGaussian() * 3).toInt()
                val r = ((p shr 16 and 0xFF) + d).coerceIn(0, 255); val v = ((p shr 8 and 0xFF) + d).coerceIn(0, 255); val b = ((p and 0xFF) + d).coerceIn(0, 255)
                im.setRGB(x, y, (r shl 16) or (v shl 8) or b)
            }
        }
        return im
    }

    private val TITRES = listOf("Bienvenue à bord", "La Terre vue d'en haut", "Une orbite en 92 minutes", "Le relais FM",
        "Les transpondeurs linéaires", "L'effet Doppler", "Suivre au rotor", "Le carnet de trafic", "Les images SSTV",
        "Le journal des passages", "QO-100 en géostationnaire", "Fin de la série")
}
