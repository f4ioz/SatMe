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
 * each was heard, and each SSDV picture the pass heard packets of — the
 * picture as it stands, put together over every pass ([AssemblageSsdv]),
 * with this pass's share. Read from [ArchiveGenesis]; nothing is decoded again.
 */
class GenesisJournalPc(private val archive: () -> ArchiveGenesis?, private val assemblage: () -> AssemblageSsdv?) {

    /** The pass's telemetry frames (« tlm ») and SSDV pictures (« ssdv »), [en]: labels in English. */
    fun json(e: JournalPassage.Entree, en: Boolean): JSONObject {
        val f = JournalPassage.fenetre(e)
        val l = runCatching { archive()?.entre(f.first, f.last, e.satName, e.catnum) }.getOrNull().orEmpty()
        val tlm = JSONArray()
        for (x in l) {
            if (x.type == Genesis.TYPE_SSDV) continue
            val lu = TelemesureGenesis.lis(x.type, x.hex)
            val o = if (lu != null) TelemesureGenesis.json(lu, en)
                else JSONObject().put("type", x.type).put("nom", TelemesureGenesis.nomType(x.type, en)).put("resume", "").put("champs", JSONArray())
            tlm.put(o.put("t", x.t).put("src", x.src))
        }
        return JSONObject().put("tlm", tlm).put("ssdv", ssdv(l.filter { it.type == Genesis.TYPE_SSDV }))
    }

    private fun ssdv(l: List<ArchiveGenesis.Entree>): JSONArray {
        val a = assemblage()
        val out = JSONArray()
        for ((cle, ps) in l.groupBy { it.cle }) {
            if (cle.isBlank()) continue
            val ici = ps.map { it.numero }.distinct().sorted()
            val o = JSONObject().put("cle", cle).put("de", ps.first().t).put("a", ps.last().t).put("ici", JSONArray(ici))
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

    companion object {
        /**
         * A picture not finished drawn as it stands, each missing block grey and hatched (not to be taken for a grey
         * sky), as a JPEG; the JPEG as it is when every block is there or nothing is known of them.
         */
        fun hachure(apercu: Triple<ByteArray, BooleanArray?, Pair<Int, Int>>): ByteArray {
            val (jpeg, vues, taille) = apercu
            if (vues == null || vues.all { it }) return jpeg
            val im = runCatching { javax.imageio.ImageIO.read(jpeg.inputStream()) }.getOrNull() ?: return jpeg
            val (mw, mh) = taille
            val out = BufferedImage(im.width, im.height, BufferedImage.TYPE_INT_RGB)
            val g = out.createGraphics()
            g.drawImage(im, 0, 0, null)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val parLigne = maxOf(1, im.width / mw)
            val fond = Color(0x3A, 0x40, 0x48); val trait = Color(0x8A, 0x92, 0x9C)
            g.stroke = BasicStroke(1.4f)
            for (m in vues.indices) {
                if (vues[m]) continue
                val x = (m % parLigne) * mw; val y = (m / parLigne) * mh
                g.color = fond; g.fillRect(x, y, mw, mh)
                g.color = trait
                val clip = g.clip; g.clipRect(x, y, mw, mh)
                var k = -mh
                while (k < mw) { g.drawLine(x + k, y + mh, x + k + mh, y); k += 6 }
                g.clip = clip
            }
            g.dispose()
            return java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(out, "jpg", it) }.toByteArray()
        }
    }
}
