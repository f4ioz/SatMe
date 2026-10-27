/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.hf

import org.json.JSONObject
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The solar and band conditions of HamQSL (N0NBH, `solarxml.php`): the
 * indices as given (strings), the HF conditions by band group day and night,
 * the VHF phenomena. HamQSL asks to be read at most hourly (its flux updates
 * hourly, the rest every three hours): kept an hour, the last good one served
 * when it does not answer (the old site kept 90 s and served nothing then).
 */
class Solaire(private val amont: Amont, horloge: () -> Long = System::currentTimeMillis) {

    private val memo = Memo<JSONObject>(amont, 60 * 60_000L, 10 * 60_000L, 2, horloge)

    /** The conditions (an empty object when none has come yet). */
    fun conditions(attenteMs: Long = 8_000L): JSONObject {
        val l = memo.lis("solaire", attenteMs) { analyse(amont.texte(URL, 256 * 1024)) }
        return l.valeur ?: JSONObject()
    }

    fun pose(o: JSONObject) = memo.pose("solaire", o)

    companion object {
        const val URL = "https://www.hamqsl.com/solarxml.php"

        /** A safe XML reader: no DTD, no external entity (the body is a third party's). */
        fun lecteurXml() = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            isExpandEntityReferences = false
            isXIncludeAware = false
        }.newDocumentBuilder()

        /**
         * `solardata`'s children with text → their tag; `calculatedconditions/band[@name,@time]` →
         * `hf_conditions[name][time]` and `hf_<name>_<time>`; `calculatedvhfconditions/phenomenon` →
         * `vhf_conditions["name|location"]`. The containers are read before the « has text » rule
         * (the old site once lost the conditions that way).
         */
        fun analyse(xml: String): JSONObject {
            val doc = lecteurXml().parse(ByteArrayInputStream(xml.trim().toByteArray(Charsets.UTF_8)))
            val sd = doc.getElementsByTagName("solardata").item(0) as? Element ?: throw IllegalArgumentException("pas de solardata")
            val out = JSONObject()
            val hf = JSONObject()
            val vhf = JSONObject()
            val enfants = sd.childNodes
            for (i in 0 until enfants.length) {
                val e = enfants.item(i) as? Element ?: continue
                when (e.tagName) {
                    "calculatedconditions" -> {
                        val bandes = e.getElementsByTagName("band")
                        for (k in 0 until bandes.length) {
                            val b = bandes.item(k) as Element
                            val nom = b.getAttribute("name").trim().take(20); val quand = b.getAttribute("time").trim().take(10)
                            val v = b.textContent.trim().take(40)
                            if (nom.isEmpty() || quand.isEmpty()) continue
                            (hf.optJSONObject(nom) ?: JSONObject().also { hf.put(nom, it) }).put(quand, v)
                            out.put("hf_${nom}_$quand", v)
                        }
                    }
                    "calculatedvhfconditions" -> {
                        val ph = e.getElementsByTagName("phenomenon")
                        for (k in 0 until ph.length) {
                            val p = ph.item(k) as Element
                            vhf.put(p.getAttribute("name").trim().take(30) + "|" + p.getAttribute("location").trim().take(30), p.textContent.trim().take(40))
                        }
                    }
                    else -> {
                        val t = e.textContent.trim()
                        if (t.isNotEmpty() && e.getElementsByTagName("*").length == 0) out.put(e.tagName.take(30), t.take(80))
                    }
                }
            }
            out.put("hf_conditions", hf)
            out.put("vhf_conditions", vhf)
            return out
        }
    }
}
