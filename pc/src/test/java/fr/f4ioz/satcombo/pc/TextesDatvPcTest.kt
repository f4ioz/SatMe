/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.phonie.ProtocolePhonie
import fr.f4ioz.satcombo.phonie.ProtocolePhonie.Message
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.sin

/** DATV subtitles (the stream's sound to the phonie) and the callsigns read in its picture. */
class TextesDatvPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-textes-datv-" + System.nanoTime()).apply { mkdirs() }
    private val ouverts = ArrayList<PhoniePc>()
    @After fun fin() { ouverts.forEach { it.arrete() }; dossier.deleteRecursively() }

    private class Faux(val texte: String) : MoteurPhonie {
        @Volatile var demarrages = 0
        @Volatile var recus = 0
        override fun demarre(): Message.Pret { demarrages++; return Message.Pret("turbo", "cpu", "int8", 0.1, "") }
        override fun transcrit(id: Long, pcm: FloatArray, langue: String, traduire: Boolean): Message {
            recus++
            return Message.Reponse(id, "es", 0.98, 0.1, listOf(ProtocolePhonie.Morceau(0.0, 2.0, texte, -0.2, 0.01, 1.2)), if (traduire) "translated" else "")
        }
        override fun arrete() {}
    }

    @Volatile private var horloge = 1_760_000_000_000L

    private fun phonie(f: Faux, st: StockageMemoire = StockageMemoire(), reel: Boolean = false): PhoniePc =
        PhoniePc(st, InstallPhoniePc(dossier), File(dossier, "phonie"), if (reel) System::currentTimeMillis else ({ horloge }), fabrique = { f }).also { ouverts += it }

    /** [n] overs of 2 s, speech-like (a syllabic rhythm on two tones), 2 s of noise between, at [rate]. */
    private fun voix(n: Int, rate: Int): ShortArray {
        val rnd = java.util.Random(5)
        val une = (0 until rate * 4).map { i ->
            val t = i.toDouble() / rate
            val v = if (t < 2.0) 0.0 else (0.5 + 0.5 * sin(2 * PI * 4 * t)).let { s -> s * s * (0.2 * sin(2 * PI * 300 * t) + 0.1 * sin(2 * PI * 1200 * t)) }
            ((v + rnd.nextGaussian() * 0.005) * 32767).toInt().toShort()
        }
        return ShortArray(une.size * n + rate * 2) { if (it < une.size * n) une[it % une.size] else (rnd.nextGaussian() * 0.005 * 32767).toInt().toShort() }
    }

    private fun pousse(pcm: ShortArray, rate: Int, ecoute: (ShortArray, Int, Int) -> Unit) {
        var i = 0
        while (i < pcm.size) {
            val k = minOf(rate / 10, pcm.size - i)
            horloge += k * 1000L / rate
            ecoute(pcm.copyOfRange(i, i + k), k, rate)
            i += k
        }
    }

    private fun attend(ms: Long, condition: () -> Boolean): Boolean {
        val fin = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fin) { if (condition()) return true; Thread.sleep(20) }
        return condition()
    }

    // ------------------------------------------------------------------ one source at a time

    @Test fun `les sous-titres prennent la place puis la rendent`() {
        val f = Faux("aquí EA4ABC")
        val st = StockageMemoire()
        val p = phonie(f, st)
        p.source = "carte"
        // Off: the DATV sound is not taken.
        pousse(voix(1, 16_000), 16_000) { a, n, r -> p.ecouteDatv(a, n, r) }
        Thread.sleep(150)
        assertEquals(0, f.demarrages); assertTrue(p.bulles().isEmpty())
        // Subtitles on, the phonie itself off: the engine runs for them alone, the card is not heard.
        p.prendsDatv(true)
        assertTrue(attend(3000) { p.etat == "prêt" })
        assertFalse(p.actif)
        pousse(voix(1, 48_000), 48_000) { a, n, r -> p.ecouteCarte(a, n, r) }
        pousse(voix(1, 16_000), 16_000) { a, n, r -> p.ecouteDatv(a, n, r) }
        assertTrue(attend(3000) { p.bulles().isNotEmpty() })
        Thread.sleep(150)
        assertEquals(listOf("datv"), p.bulles().map { it.source })
        assertEquals("datv", p.json(0).getString("sourceActive"))
        // Off again: the engine stops (the phonie was off), its source never changed.
        p.prendsDatv(false)
        assertEquals("coupé", p.etat)
        assertEquals("carte", p.source)
        // The phonie on with the card: the subtitles take over, then give it back.
        p.actif = true
        p.prendsDatv(true)
        pousse(voix(1, 48_000), 48_000) { a, n, r -> p.ecouteCarte(a, n, r) }
        pousse(voix(1, 48_000), 48_000) { a, n, r -> p.ecouteSdr(a, n, r, null) }
        Thread.sleep(300)
        assertEquals(1, p.bulles().size)
        p.prendsDatv(false)
        assertEquals("prêt", p.etat)
        pousse(voix(1, 16_000), 16_000) { a, n, r -> p.ecouteDatv(a, n, r) }
        pousse(voix(1, 48_000), 48_000) { a, n, r -> p.ecouteCarte(a, n, r) }
        assertTrue(attend(3000) { p.bulles().size == 2 })
        Thread.sleep(150)
        assertEquals(listOf("datv", "carte"), p.bulles().map { it.source })
        assertEquals("carte", st.getString("pc_phonie_source", ""))
    }

    @Test fun `rien sans le choix`() {
        var appels = 0
        var sousTitres: Boolean? = null
        val src = TextesDatvPc.Source({ true }, { true }, { appels++; ByteArray(0) }, { "" }, { 10_491_500L }, { sousTitres = it })
        val t = TextesDatvPc(StockageMemoire(), src, phonie(Faux("x")), { horloge }, outil = { appels++; "/bin/false" })
        repeat(3) { t.tic(); horloge += 5000 }
        assertEquals(0, appels); assertEquals(null, sousTitres)
        assertFalse(t.json().getBoolean("soustitres")); assertFalse(t.json().getBoolean("ocr"))
    }

    @Test fun `les sous-titres suivent la reception`() {
        var actif = false
        var sousTitres = false
        val f = Faux("x")
        val p = phonie(f)
        val src = TextesDatvPc.Source({ actif }, { actif }, { null }, { "" }, { 10_491_500L }, { sousTitres = it })
        val t = TextesDatvPc(StockageMemoire(), src, p, { horloge })
        t.action(mapOf("soustitres" to "1"))
        assertFalse(sousTitres); assertFalse(p.datv)
        actif = true; t.tic()
        assertTrue(sousTitres); assertTrue(p.datv); assertTrue(t.json().getBoolean("soustitresActifs"))
        actif = false; t.tic()
        assertFalse(sousTitres); assertFalse(p.datv)
        actif = true; t.tic(); t.action(mapOf("soustitres" to "0"))
        assertFalse(sousTitres); assertFalse(p.datv)
    }

    // ------------------------------------------------------------------ the stream's sound

    private fun ffmpeg(): String? = RenduPc.ffmpeg()

    /** A transport stream as a station sends it (MPEG-2 picture, AAC sound) with [pcm] as its sound. */
    private fun ts(pcm: ShortArray, rate: Int): File {
        val ff = ffmpeg()!!
        val wav = File(dossier, "voix.wav")
        val o = ByteArray(pcm.size * 2)
        pcm.forEachIndexed { i, s -> o[2 * i] = s.toByte(); o[2 * i + 1] = (s.toInt() shr 8).toByte() }
        javax.sound.sampled.AudioSystem.write(javax.sound.sampled.AudioInputStream(o.inputStream(),
            javax.sound.sampled.AudioFormat(rate.toFloat(), 16, 1, true, false), pcm.size.toLong()),
            javax.sound.sampled.AudioFileFormat.Type.WAVE, wav)
        val sortie = File(dossier, "datv.ts")
        val p = ProcessBuilder(ff, "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25", "-i", wav.path,
            "-shortest", "-c:v", "mpeg2video", "-c:a", "aac", "-b:a", "96k", "-f", "mpegts", sortie.path).redirectErrorStream(true).start()
        val dit = p.inputStream.readBytes().decodeToString()
        assertEquals(dit, 0, p.waitFor())
        return sortie
    }

    @Test fun `le son du flux arrive au decoupeur de la phonie`() {
        assumeTrue(ffmpeg() != null)
        val rate = 48_000
        val fichier = ts(voix(3, rate), rate)
        val f = Faux("CQ de EA4ABC")
        val p = phonie(f, reel = true)
        p.prendsDatv(true)
        var echantillons = 0L
        val son = SonDatvPc(ffmpeg()!!) { a, n, r -> assertEquals(16_000, r); echantillons += n; p.ecouteDatv(a, n, r) }
        assertTrue(son.demarre())
        // The stream's blocks as LongMynd sends them (7 packets of 188 bytes).
        fichier.readBytes().asList().chunked(1316).forEach { b -> son.entree?.write(b.toByteArray()) }
        son.entree?.close()
        assertTrue(attend(15_000) { p.bulles().size >= 3 })
        son.arrete()
        assertTrue("$echantillons", echantillons > 16_000L * 12)
        val b = p.bulles()
        assertTrue(b.all { it.source == "datv" })
        assertEquals("EA4ABC", b.first().elements.first { it.genre.name == "INDICATIF" }.valeur)
        assertTrue(f.recus >= 3)
    }

    // ------------------------------------------------------------------ callsigns in the picture

    private fun image(texte: String, l: Int = 640, h: Int = 360, fond: Color = Color(20, 30, 70)): ByteArray {
        val img = BufferedImage(l, h, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            // A picture with colour bars, the callsign written over the top band.
            val barres = listOf(Color.WHITE, Color.YELLOW, Color.CYAN, Color.GREEN, Color.MAGENTA, Color.RED, Color.BLUE)
            barres.forEachIndexed { i, c -> color = c; fillRect(i * l / barres.size, h / 3, l / barres.size + 1, h / 3) }
            color = fond; fillRect(0, 0, l, h / 3); fillRect(0, 2 * h / 3, l, h / 3)
            color = Color.WHITE; font = Font(Font.SANS_SERIF, Font.BOLD, h / 9)
            drawString(texte, l / 12, h / 5)
            font = Font(Font.SANS_SERIF, Font.PLAIN, h / 20)
            drawString("QO-100 10491.500 MHz", l / 12, h - h / 10)
            dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    @Test fun `la preparation agrandit et met le texte en sombre`() {
        val img = ImageIO.read(image("EA4ABC").inputStream())
        val tout = TextesDatvPc.net(img, 2)
        assertEquals(1280, tout.width); assertEquals(BufferedImage.TYPE_BYTE_GRAY, tout.type)
        // The dark background made light.
        assertTrue(tout.raster.getSample(4, 4, 0) > 200)
        // One line alone: enlarged, two levels only, black text on white whatever its colours.
        val carte = MirePc.carte(640, 360, "F4IOZ", "JN18FS", "DATV")
        val l = TextesDatvPc.ligne(carte, TextesDatvPc.Companion.Ligne(150, 236, 220, 40, "FAIOZ JN18FS"))
        assertTrue(l.height >= 64 * 1.3); assertEquals(BufferedImage.TYPE_BYTE_GRAY, l.type)
        val px = IntArray(l.width * l.height).also { l.raster.getSamples(0, 0, l.width, l.height, 0, it) }
        assertTrue(px.all { it == 0 || it == 255 })
        val noirs = px.count { it == 0 }
        assertTrue("$noirs", noirs in px.size / 50..px.size / 3)
        assertEquals(255, l.raster.getSample(0, 0, 0))
        // The lines of a read: its words with two signs or more, grouped, in picture pixels.
        val tsv = "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n" +
            "5\t1\t1\t1\t1\t1\t320\t520\t120\t50\t90\tFAIOZ\n5\t1\t1\t1\t1\t2\t470\t520\t200\t52\t90\tJN18FS\n" +
            "5\t1\t2\t1\t1\t1\t20\t20\t30\t30\t60\t=\n"
        val lignes = TextesDatvPc.lignes(tsv, 2, 640, 360)
        assertEquals(listOf(TextesDatvPc.Companion.Ligne(160, 260, 175, 26, "FAIOZ JN18FS")), lignes)
    }

    @Test fun `un faux tesseract lu deux fois de suite`() {
        val args = File(dossier, "args.txt")
        val prog = File(dossier, "tesseract").apply {
            // The whole picture asked for its words' boxes; the lines alone, as text.
            val tsv = "level\\tpage_num\\tblock_num\\tpar_num\\tline_num\\tword_num\\tleft\\ttop\\twidth\\theight\\tconf\\ttext\\n" +
                "5\\t1\\t1\\t1\\t1\\t1\\t100\\t50\\t60\\t40\\t90\\tCQ\\n5\\t1\\t1\\t1\\t1\\t2\\t180\\t50\\t200\\t40\\t90\\tEA4ABC\\n" +
                "5\\t1\\t2\\t1\\t1\\t1\\t100\\t600\\t200\\t40\\t90\\tFAIOZ\\n"
            writeText("#!/bin/sh\necho \"$@\" >> '${args.path}'\ntest -s \"$1\" || exit 3\n" +
                "case \"$*\" in *tsv*) printf '$tsv' ;; *) echo 'CQ EA4ABC'; echo 'FAIOZ JN11 10491.500 1500KS' ;; esac\n")
            setExecutable(true)
        }
        val jpeg = image("EA4ABC")
        var service = "F4IOZ"
        val src = TextesDatvPc.Source({ true }, { true }, { jpeg }, { service }, { 10_491_500L }, {})
        val t = TextesDatvPc(StockageMemoire(), src, null, { horloge }, outil = { prog.path }, periodeMs = 3000)
        t.ocr = true
        t.tic()
        assertTrue(attend(5000) { t.lectures == 1L && !t.json().getBoolean("occupe") })
        // One read only: only the one the service name vouches for (FAIOZ read, F4IOZ meant).
        val un = t.json()
        assertEquals(1, un.getJSONArray("lus").length())
        un.getJSONArray("lus").getJSONObject(0).let { lu ->
            assertEquals("F4IOZ", lu.getString("c")); assertEquals("service", lu.getString("garant")); assertEquals("FAIOZ", lu.getString("mot"))
            assertTrue(lu.getBoolean("accord"))
        }
        assertTrue(un.getString("texte"), un.getString("texte").contains("F4IOZ JN11"))
        assertFalse(un.getString("texte").contains("FAIOZ"))
        t.tic()
        Thread.sleep(100)
        assertEquals(1L, t.lectures)  // not before its period
        horloge += 3000
        t.tic()
        assertTrue(attend(5000) { t.lectures == 2L && !t.json().getBoolean("occupe") })
        val j = t.json()
        val lu = j.getJSONArray("lus").getJSONObject(0)
        assertEquals(listOf("EA4ABC", "F4IOZ"), (0 until 2).map { j.getJSONArray("lus").getJSONObject(it).getString("c") }.sorted())
        val ea = (0 until 2).map { j.getJSONArray("lus").getJSONObject(it) }.first { it.getString("c") == "EA4ABC" }
        assertEquals("ES", ea.getString("drapeau")); assertFalse(ea.getBoolean("accord")); assertEquals("", ea.getString("garant"))
        assertTrue(j.getBoolean("qo100")); assertTrue(j.getString("texte").contains("CQ EA4ABC"))
        service = "EA4ABC"
        assertTrue(t.json().getJSONArray("lus").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.first { it.getString("c") == "EA4ABC" }.getBoolean("accord"))
        assertTrue(lu.has("lectures"))
        // Each read: the whole picture (sparse, with the boxes), its lines alone twice (a block each), callsign characters only.
        val l = args.readLines()
        assertEquals(6, l.size)
        assertTrue(l[0], l[0].contains("stdout --oem 1 --psm 11 -c tessedit_char_whitelist=" + TextesDatvPc.LISTE))
        assertTrue(l[0], l[0].endsWith("tsv"))
        assertTrue(l[1].contains("--psm 6")); assertFalse(l[1].contains("tsv")); assertTrue(l[2].contains("--psm 6"))
        // Off: forgotten, nothing read.
        t.ocr = false
        horloge += 3000; t.tic(); Thread.sleep(100)
        assertEquals(6, args.readLines().size)
        assertEquals(0, t.json().getJSONArray("lus").length())
    }

    @Test fun `le vrai tesseract quand il est la`() {
        val prog = OutilsPc.trouve("tesseract")
        assumeTrue(prog != null)
        val t = TextesDatvPc(StockageMemoire(), TextesDatvPc.Source({ true }, { true }, { null }, { "" }, { 0L }, {}), null, { horloge })
        val c1 = t.lisImage(prog!!, image("EA4ABC"))
        println("tesseract : « ${t.dernierTexte} » → $c1")
        val c2 = t.lisImage(prog, image("CQ DE G4XYZ", 1280, 720, Color(10, 10, 10)))
        println("tesseract : « ${t.dernierTexte} » → $c2")
        assertTrue(c1.contains("EA4ABC"))
        assertTrue(c2.contains("G4XYZ"))
    }
}
