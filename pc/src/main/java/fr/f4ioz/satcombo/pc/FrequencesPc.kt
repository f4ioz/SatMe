/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.FichiersFrequences
import fr.f4ioz.satcombo.data.TleEntry
import fr.f4ioz.satcombo.data.Transmitter
import fr.f4ioz.satcombo.plateforme.Stockage
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Réglages › « Fréquences : SatPC32 et Gpredict »: the satellites'
 * frequencies exported as `Doppler.SQF` or `.trsp` files, and such files
 * imported ([FichiersFrequences]). What is imported is kept here (the
 * settings, one JSON) and joins each satellite's transmitter list after
 * SatNOGS DB's ([pour]); an import replaces what an earlier one gave the
 * same satellite. Nothing is sent to the rig by an import: the list only.
 */
class FrequencesPc(
    private val stockage: Stockage,
    private val satellites: () -> List<TleEntry>,
    /** SatNOGS DB's transmitters already known (nothing downloaded). */
    private val connus: () -> Map<Int, List<Transmitter>>,
    private val favoris: () -> Set<Int>,
    private val suivi: () -> Int?,
    /** Called with the satellites an import changed (the station reads the followed one's list again). */
    private val change: (Set<Int>) -> Unit = {},
) {
    @Volatile private var importes: Map<Int, List<Transmitter>> = lis()

    private fun lis(): Map<Int, List<Transmitter>> = runCatching {
        val o = JSONObject(stockage.getString(CLE, null) ?: return emptyMap())
        o.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { n -> n to o.getJSONArray(k).let { a -> (0 until a.length()).map { versEmetteur(a.getJSONObject(it)) } } } }.toMap()
    }.getOrDefault(emptyMap())

    private fun garde() {
        val o = JSONObject()
        importes.forEach { (n, l) -> o.put(n.toString(), JSONArray(l.map(::json))) }
        stockage.edit().putString(CLE, o.toString()).apply()
    }

    /** The satellites that have imported frequencies. */
    fun satellitesImportes(): Set<Int> = importes.keys

    /** The transmitters imported for [catnum]. */
    fun pour(catnum: Int): List<Transmitter> = importes[catnum].orEmpty()

    /** What export takes for [catnum]: SatNOGS DB's live ones with a downlink, then the imported. */
    fun emetteurs(catnum: Int): List<Transmitter> =
        connus()[catnum].orEmpty().filter { it.alive && it.downlinkLowHz != null } + pour(catnum)

    /** The satellites [quoi] names: « suivi », « tous » (every one with a frequency known), else the favourites. */
    fun choix(quoi: String?): List<Pair<TleEntry, List<Transmitter>>> {
        val sats = satellites()
        val nums: Collection<Int> = when (quoi) {
            "suivi" -> listOfNotNull(suivi())
            "tous" -> sats.map { it.catalogNumber }
            else -> favoris()
        }
        val parNum = sats.associateBy { it.catalogNumber }
        return nums.distinct().mapNotNull { n -> parNum[n]?.let { s -> s to emetteurs(n) } }.filter { it.second.isNotEmpty() }.sortedBy { it.first.name.uppercase() }
    }

    fun sqf(quoi: String?): String = FichiersFrequences.ecritSqf(choix(quoi).map { (s, l) -> s.name to l })

    fun trsp(catnum: Int): String = FichiersFrequences.ecritTrsp(emetteurs(catnum))

    /** Every satellite [quoi] names in its own `NNNNN.trsp`, zipped. */
    fun trspZip(quoi: String?): ByteArray {
        val b = ByteArrayOutputStream()
        ZipOutputStream(b).use { z ->
            for ((s, l) in choix(quoi)) {
                val t = FichiersFrequences.ecritTrsp(l).takeIf { it.isNotBlank() } ?: continue
                z.putNextEntry(ZipEntry("${s.catalogNumber}.trsp")); z.write(t.toByteArray(Charsets.UTF_8)); z.closeEntry()
            }
        }
        return b.toByteArray()
    }

    /**
     * A file sent by the page: [nom] tells a `.trsp` (its NORAD number, or
     * [catnum]) from an SQF. Returns what was imported and what was not.
     */
    @Synchronized fun importe(nom: String, texte: String, catnum: Int? = null): JSONObject {
        val trsp = nom.endsWith(".trsp", true) || (catnum != null && texte.contains("DOWN_LOW", true))
        val imp = if (trsp) {
            val n = catnum ?: FichiersFrequences.numeroDuFichier(nom)
                ?: return JSONObject().put("ok", false).put("erreur", "« $nom » : un fichier .trsp se nomme par le numéro NORAD du satellite (25544.trsp)")
            val lu = FichiersFrequences.lisTrsp(texte)
            val connu = satellites().any { it.catalogNumber == n }
            FichiersFrequences.Import(if (connu && lu.lus.isNotEmpty()) mapOf(n to lu.lus) else emptyMap(),
                if (!connu) mapOf("n° $n" to lu.lus.size) else emptyMap(), lu.illisibles)
        } else FichiersFrequences.associe(FichiersFrequences.lisSqf(texte), satellites().map { it.name to it.catalogNumber })
        if (imp.parSatellite.isNotEmpty()) {
            importes = importes + imp.parSatellite
            garde()
            change(imp.parSatellite.keys)
        }
        val noms = satellites().associate { it.catalogNumber to it.name }
        return JSONObject().put("ok", true).put("fichier", nom).put("format", if (trsp) "trsp" else "sqf").put("n", imp.nombre)
            .put("satellites", JSONArray(imp.parSatellite.map { (n, l) -> JSONObject().put("catnum", n).put("nom", noms[n] ?: n.toString()).put("n", l.size) }))
            .put("inconnus", JSONArray(imp.inconnus.map { (k, v) -> JSONObject().put("nom", k).put("lignes", v) }))
            .put("illisibles", JSONArray(imp.illisibles.take(20).map { (i, l) -> JSONObject().put("ligne", i).put("texte", l.take(120)) }))
    }

    /** Forgets every imported frequency. */
    @Synchronized fun oublie() {
        val avant = importes.keys
        importes = emptyMap(); garde(); change(avant)
    }

    /** The card's summary. */
    fun json(): JSONObject {
        val noms = satellites().associate { it.catalogNumber to it.name }
        return JSONObject().put("n", importes.values.sumOf { it.size }).put("favoris", favoris().size)
            .put("satellites", JSONArray(importes.map { (n, l) -> JSONObject().put("catnum", n).put("nom", noms[n] ?: n.toString()).put("n", l.size) }))
    }

    companion object {
        const val CLE = "pc_frequences_importees"

        fun json(t: Transmitter): JSONObject = JSONObject().put("d", t.description).put("m", t.mode ?: JSONObject.NULL)
            .put("ul", t.uplinkLowHz ?: JSONObject.NULL).put("uh", t.uplinkHighHz ?: JSONObject.NULL)
            .put("dl", t.downlinkLowHz ?: JSONObject.NULL).put("dh", t.downlinkHighHz ?: JSONObject.NULL)
            .put("i", t.invert).put("ty", t.type).put("b", t.baud ?: JSONObject.NULL)

        fun versEmetteur(o: JSONObject): Transmitter {
            fun l(k: String) = if (o.isNull(k)) null else o.optLong(k)
            return Transmitter(o.optString("d"), if (o.isNull("m")) null else o.optString("m"), l("ul"), l("uh"), l("dl"), l("dh"),
                o.optBoolean("i"), true, o.optString("ty"), if (o.isNull("b")) null else o.optDouble("b"))
        }
    }
}
