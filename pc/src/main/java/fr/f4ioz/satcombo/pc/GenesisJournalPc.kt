/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.telemesure.ArchiveGenesis
import fr.f4ioz.satcombo.telemesure.AssemblageSsdv
import fr.f4ioz.satcombo.telemesure.Genesis
import fr.f4ioz.satcombo.telemesure.TelemesureGenesis
import org.json.JSONArray
import org.json.JSONObject
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/**
 * What a GENESIS satellite (HADES-SA…) sent during a journal pass, for its
 * page: the telemetry frames made readable ([TelemesureGenesis]) at the time
 * each was heard — one by one, and gathered by kind (« Données du passage »:
 * every value of every frame, the BBS messages, the counts) — and each SSDV
 * picture the pass heard packets of: the picture as it stands, put together
 * over every pass ([AssemblageSsdv]), with this pass's share, and as it grew
 * during the pass, packet by packet ([etapes], [imageA]). Read from
 * [ArchiveGenesis]; nothing is decoded again.
 */
class GenesisJournalPc(private val archive: () -> ArchiveGenesis?, private val assemblage: () -> AssemblageSsdv?) {

    /**
     * One picture's growth during a pass: the packets it held before the pass ([avant], heard on an earlier one)
     * and those the pass heard ([recus]: time first heard in the pass, packet number; time order).
     */
    class Etapes(val cle: String, val titre: String, val avant: Set<Int>, val recus: List<Pair<Long, Int>>) {
        /** The packets the pass had heard by [t] (ms). */
        fun ici(t: Long): Set<Int> = recus.filter { it.first <= t }.map { it.second }.toSet()
        /** Those shown at [t]: the earlier passes', and this one's heard by then. */
        fun visibles(t: Long): Set<Int> = avant + ici(t)
        /** The first packet the pass heard; null when none. */
        val debut: Long? get() = recus.firstOrNull()?.first
        val fin: Long? get() = recus.lastOrNull()?.first
    }

    private fun entrees(e: JournalPassage.Entree): List<ArchiveGenesis.Entree> {
        val f = JournalPassage.fenetre(e)
        return runCatching { archive()?.entre(f.first, f.last, e.satName, e.catnum) }.getOrNull().orEmpty()
    }

    /**
     * The pass's telemetry frames (« tlm »), gathered by kind (« groupes »), its BBS messages (« bbs »), its SSDV
     * pictures (« ssdv », each with « recu » [[t, n]…] and « avant »), the counts (« compteurs »); [en]: in English.
     */
    fun json(e: JournalPassage.Entree, en: Boolean): JSONObject {
        val l = entrees(e)
        val tlm = JSONArray()
        val lues = ArrayList<Pair<ArchiveGenesis.Entree, TelemesureGenesis.Lecture?>>()
        for (x in l) {
            if (x.type == Genesis.TYPE_SSDV) continue
            val lu = TelemesureGenesis.lis(x.type, x.hex)
            lues += x to lu
            val o = if (lu != null) TelemesureGenesis.json(lu, en)
                else JSONObject().put("type", x.type).put("nom", TelemesureGenesis.nomType(x.type, en)).put("resume", "").put("champs", JSONArray())
            tlm.put(o.put("t", x.t).put("src", x.src))
        }
        val paquets = l.filter { it.type == Genesis.TYPE_SSDV }
        val images = ssdv(e, paquets)
        val compteurs = JSONObject().put("trames", lues.size).put("types", lues.map { it.first.type }.distinct().size)
            .put("paquets", paquets.size).put("images", images.length())
            .put("nouveaux", (0 until images.length()).sumOf { images.getJSONObject(it).optInt("nouveaux") })
            .put("debit", l.lastOrNull { it.debit.isNotBlank() }?.debit ?: "")
        return JSONObject().put("tlm", tlm).put("ssdv", images).put("groupes", groupes(lues, en)).put("bbs", bbs(lues))
            .put("compteurs", compteurs)
    }

    /** Whether anything of the pass was kept, and the rate its frames were heard at (« 200 », « 800 », null not known). */
    fun resume(e: JournalPassage.Entree): Pair<Boolean, String?> {
        val l = entrees(e)
        return l.isNotEmpty() to l.lastOrNull { it.debit.isNotBlank() }?.debit
    }

    /** A picture put together ([AssemblageSsdv]), by its key. */
    fun photo(cle: String): AssemblageSsdv.Photo? = assemblage()?.photo(cle)

    /** A picture's packet count, known or guessed (0: not known). */
    fun total(cle: String): Int = assemblage()?.photo(cle)?.totalEstime()?.first ?: 0

    /** One picture's growth for each picture the pass heard packets of (from the archive, else the assembly's own times). */
    fun etapes(e: JournalPassage.Entree, l: List<ArchiveGenesis.Entree> = entrees(e).filter { it.type == Genesis.TYPE_SSDV }): List<Etapes> {
        val a = assemblage()
        val f = JournalPassage.fenetre(e)
        return l.groupBy { it.cle }.filterKeys { it.isNotBlank() }.map { (cle, ps) ->
            val ph = a?.photo(cle)
            val premiers = HashMap<Int, Long>()
            for (p in ps) premiers.merge(p.numero, p.t) { x, y -> minOf(x, y) }
            val heures = ph?.heures().orEmpty()
            // Heard live before the archive kept them: the assembly's own times within the pass.
            for ((n, ts) in heures) ts.firstOrNull { it in f }?.let { t -> premiers.merge(n, t) { x, y -> minOf(x, y) } }
            // Held before the pass: first heard before its window.
            val avant = heures.filter { (_, ts) -> ts.minOrNull()?.let { it < f.first } == true }.keys
            Etapes(cle, ph?.let { it.indicatif + " n° " + it.numero } ?: cle, avant.toSet(),
                premiers.entries.map { it.value to it.key }.sortedWith(compareBy({ it.first }, { it.second })))
        }
    }

    private fun ssdv(e: JournalPassage.Entree, l: List<ArchiveGenesis.Entree>): JSONArray {
        val a = assemblage()
        val out = JSONArray()
        val et = etapes(e, l).associateBy { it.cle }
        for ((cle, ps) in l.groupBy { it.cle }) {
            if (cle.isBlank()) continue
            val ici = ps.map { it.numero }.distinct().sorted()
            val o = JSONObject().put("cle", cle).put("de", ps.first().t).put("a", ps.last().t).put("ici", JSONArray(ici))
            et[cle]?.let { x ->
                o.put("recu", JSONArray(x.recus.map { (t, n) -> JSONArray().put(t).put(n) })).put("avant", JSONArray(x.avant.sorted()))
            }
            val ph = a?.photo(cle)
            if (ph != null) {
                val d = a.json(ph)
                for (k in listOf("indicatif", "numero", "recus", "total", "estime", "pourcent", "complete", "largeur", "hauteur", "passages", "bande", "satellite"))
                    o.put(k, d.opt(k))
                o.put("titre", ph.indicatif + " n° " + ph.numero)
                // Which packets this pass brought first (the others it heard again).
                o.put("nouveaux", ph.passages.filter { p -> ps.any { it.t in p.debut..p.fin } }.sumOf { it.paquets })
                o.put("contributeurs", ph.contributeurs().size)
            } else o.put("titre", cle).put("recus", ici.size)
            out.put(o)
        }
        return out
    }

    // ------------------------------------------------------------ the picture as it grew

    private val cache = object : LinkedHashMap<String, BufferedImage>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BufferedImage>?) = size > 24
    }

    /**
     * Picture [cle] as pass [e] showed it at [t]: the packets of the earlier passes darkened, this pass's heard by
     * then bright, the rest hatched; null when the picture is not known. Before the pass's first packet (and nothing
     * from earlier): all hatched, at the picture's size.
     */
    fun imageA(e: JournalPassage.Entree, cle: String, t: Long): BufferedImage? {
        val ph = assemblage()?.photo(cle) ?: return null
        val x = etapes(e).firstOrNull { it.cle == cle } ?: Etapes(cle, cle, emptySet(), emptyList())
        return image(ph, x.avant, x.ici(t))
    }

    /** The same, as a JPEG for the page. */
    fun jpegA(e: JournalPassage.Entree, cle: String, t: Long): ByteArray? = imageA(e, cle, t)?.let { jpeg(it) }

    /** Picture [ph] from packets [avant] (darkened) and [ici] (bright), through the cache. */
    fun image(ph: AssemblageSsdv.Photo, avant: Set<Int>, ici: Set<Int>): BufferedImage {
        val k = ph.id + "|" + ph.recus + "|" + avant.sorted().hashCode() + "|" + ici.sorted().hashCode() + "|" + (avant + ici).size
        synchronized(cache) { cache[k] }?.let { return it }
        val tous = ph.apercuDe(avant + ici)
        val im = if (tous == null) vide(ph) else dessine(tous, ph.apercuDe(ici)?.second ?: BooleanArray(tous.second?.size ?: 0))
        synchronized(cache) { cache[k] = im }
        return im
    }

    companion object {
        /** Grey and hatched: a missing block (not to be taken for a grey sky). */
        private val FOND = Color(0x3A, 0x40, 0x48)
        private val TRAIT = Color(0x8A, 0x92, 0x9C)

        fun jpeg(im: BufferedImage): ByteArray = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(im, "jpg", it) }.toByteArray()

        /** A picture nothing of which is there yet: hatched all over, at its size. */
        fun vide(ph: AssemblageSsdv.Photo): BufferedImage {
            val w = ph.largeur.coerceAtLeast(16); val h = ph.hauteur.coerceAtLeast(16)
            val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = out.createGraphics()
            hachure(g, 0, 0, w, h)
            g.dispose()
            return out
        }

        private fun hachure(g: java.awt.Graphics2D, x: Int, y: Int, mw: Int, mh: Int) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.stroke = BasicStroke(1.4f)
            g.color = FOND; g.fillRect(x, y, mw, mh)
            g.color = TRAIT
            val clip = g.clip; g.clipRect(x, y, mw, mh)
            var k = -mh
            while (k < mw) { g.drawLine(x + k, y + mh, x + k + mh, y); k += 6 }
            g.clip = clip
        }

        /**
         * [apercu] drawn: each MCU not shown hatched; each shown but not among [vifs] (an earlier pass's) darkened;
         * [vifs] null: none darkened. The JPEG decoded as it is when it cannot be read.
         */
        fun dessine(apercu: Triple<ByteArray, BooleanArray?, Pair<Int, Int>>, vifs: BooleanArray?): BufferedImage {
            val (jpeg, vues, taille) = apercu
            val im = runCatching { javax.imageio.ImageIO.read(jpeg.inputStream()) }.getOrNull() ?: return BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)
            val out = BufferedImage(im.width, im.height, BufferedImage.TYPE_INT_RGB)
            val g = out.createGraphics()
            g.drawImage(im, 0, 0, null)
            if (vues != null) {
                val (mw, mh) = taille
                val parLigne = maxOf(1, im.width / mw)
                val sombre = Color(6, 10, 16, 150)
                for (m in vues.indices) {
                    val x = (m % parLigne) * mw; val y = (m / parLigne) * mh
                    if (!vues[m]) hachure(g, x, y, mw, mh)
                    else if (vifs != null && (m >= vifs.size || !vifs[m])) { g.color = sombre; g.fillRect(x, y, mw, mh) }
                }
            }
            g.dispose()
            return out
        }

        /**
         * A picture not finished drawn as it stands, each missing block grey and hatched (not to be taken for a grey
         * sky), as a JPEG; the JPEG as it is when every block is there or nothing is known of them.
         */
        fun hachure(apercu: Triple<ByteArray, BooleanArray?, Pair<Int, Int>>): ByteArray {
            val (jpeg, vues, _) = apercu
            if (vues == null || vues.all { it }) return jpeg
            return jpeg(dessine(apercu, null))
        }

        /**
         * The pass's frames gathered by kind (a time series by its variable): their times, and each value along
         * them (its label, unit; null where a frame lacks it), the latest series' samples; in the order first heard.
         */
        fun groupes(l: List<Pair<ArchiveGenesis.Entree, TelemesureGenesis.Lecture?>>, en: Boolean): JSONArray {
            val par = LinkedHashMap<String, MutableList<Pair<ArchiveGenesis.Entree, TelemesureGenesis.Lecture?>>>()
            for (x in l) {
                val lu = x.second
                if (lu?.type == 15) continue
                val k = x.first.type.toString() + (lu?.serie?.let { "/" + it.variable } ?: "")
                par.getOrPut(k) { ArrayList() } += x
            }
            val out = JSONArray()
            for ((_, fs) in par) {
                val lu = fs.firstNotNullOfOrNull { it.second }
                val type = fs.first().first.type
                val nom = TelemesureGenesis.nomType(type, en) + (lu?.serie?.let { " — " + (if (en) it.en else it.fr) } ?: "")
                val champs = LinkedHashMap<String, TelemesureGenesis.Champ>()
                for ((_, x) in fs) x?.champs?.forEach { c -> champs.putIfAbsent(c.cle, c) }
                val o = JSONObject().put("type", type).put("nom", nom).put("n", fs.size).put("t", JSONArray(fs.map { it.first.t }))
                o.put("champs", JSONArray(champs.values.map { c ->
                    val vs = fs.map { (_, x) -> x?.champ(c.cle) }
                    JSONObject().put("k", c.cle).put("l", c.libelle(en)).put("u", c.u)
                        .put("v", JSONArray(vs.map { it?.takeIf { y -> y.texte(en) == null }?.v ?: JSONObject.NULL }))
                        .put("x", JSONArray(vs.map { it?.texte(en) ?: JSONObject.NULL }))
                }))
                fs.lastOrNull { it.second?.serie != null }?.second?.serie?.let { s ->
                    o.put("serie", JSONObject().put("nom", if (en) s.en else s.fr).put("u", s.u).put("pas", s.pasS)
                        .put("v", JSONArray(s.valeurs.map { it ?: JSONObject.NULL })))
                }
                fs.lastOrNull { it.second?.horloge != null }?.second?.horloge?.let { o.put("horloge", it) }
                out.put(o)
            }
            return out
        }

        /** The BBS messages of the pass's BBS frames, each once (callsign and text), when first heard. */
        fun bbs(l: List<Pair<ArchiveGenesis.Entree, TelemesureGenesis.Lecture?>>): JSONArray {
            val vus = LinkedHashMap<String, JSONObject>()
            for ((x, lu) in l) for (b in lu?.bbs.orEmpty()) {
                if (b.indicatif.isBlank()) continue
                vus.putIfAbsent(b.indicatif + "\u0000" + b.message, JSONObject().put("de", b.indicatif).put("m", b.message).put("voix", b.tramesVoix).put("t", x.t))
            }
            return JSONArray(vus.values)
        }
    }
}
