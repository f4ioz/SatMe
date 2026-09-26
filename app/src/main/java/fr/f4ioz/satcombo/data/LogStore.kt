/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A minimal logged contact: time + satellite + pointing, no callsign (yet). */
data class LogEntry(
    val timeMs: Long,
    val satName: String,
    val catnum: Int,
    val azimuthDeg: Double,
    val elevationDeg: Double,
    val myLocator: String = "",
    /**
     * Every big square the station legitimately sits in when it operates close
     * to a grid line: "JN18,JN19", or four of them on a corner. Comma-separated
     * 4-character squares, the first one being ours. Empty when the site is
     * comfortably inside a single square.
     */
    val myGrids: String = "",
    val callsign: String = "",
    val theirLocator: String = "",
    val note: String = "",
    /**
     * Ce que l'annuaire apprend du correspondant : nom, ville, courriel.
     *
     * Ces trois-là ne se retrouvent pas dans la géométrie du passage — ils
     * viennent de QRZ ou de la main. Wavelog a un champ pour chacun, et sans
     * eux il faut aller les remplir un par un dans son écran, alors que
     * l'information était déjà connue au moment du dépôt.
     */
    val nom: String = "",
    val qth: String = "",
    val courriel: String = "",
    /** Mode tel qu'annoncé par le transpondeur ou choisi à la main : FM, USB… */
    val mode: String = "",
    /** Report envoyé et reçu. Vides tant que l'opérateur ne les a pas saisis. */
    val rstSent: String = "",
    val rstRcvd: String = "",
    /** Descente et montée au repos, en mégahertz, pour BAND et SAT_MODE. */
    val downlinkMhz: Double = 0.0,
    val uplinkMhz: Double = 0.0,
    /**
     * Quand ce contact est parti au carnet en ligne, ou 0 s'il n'y est pas.
     *
     * Sans cette marque, chaque envoi repousserait tout le carnet et Wavelog
     * accumulerait les doublons — il accepte ce qu'on lui donne, il ne
     * dédoublonne pas. L'instant plutôt qu'un simple oui : c'est ce qui permet
     * de dire « déposé hier » et de retrouver l'ordre des choses si le carnet
     * d'en face perd quelque chose.
     */
    val envoyeMs: Long = 0L,
    /**
     * D'où vient [theirLocator] : saisi à la main, proposé par la mémoire, ou
     * inconnu. Impossible à reconstituer après coup, et c'est ce qui dira un
     * jour si c'est la base qui a menti ou la frappe qui a fauté.
     */
    val locatorOrigine: String = ""
)

/** Persists quick log entries to disk (filesDir/qso_log.json). */
class LogStore(context: Context) {
    private val file = context.filesDir.resolve("qso_log.json")

    fun load(): List<LogEntry> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                LogEntry(
                    timeMs = o.getLong("t"),
                    satName = o.optString("s"),
                    catnum = o.optInt("c"),
                    azimuthDeg = o.optDouble("az"),
                    elevationDeg = o.optDouble("el"),
                    myLocator = o.optString("ml"),
                    myGrids = o.optString("mg"),
                    callsign = o.optString("cs"),
                    theirLocator = o.optString("tl"),
                    note = o.optString("n"),
                    mode = o.optString("md"),
                    rstSent = o.optString("rs"),
                    rstRcvd = o.optString("rr"),
                    downlinkMhz = o.optDouble("dl", 0.0),
                    uplinkMhz = o.optDouble("ul", 0.0),
                    envoyeMs = o.optLong("ev", 0L),
                    locatorOrigine = o.optString("lo"),
                    nom = o.optString("nm"),
                    qth = o.optString("qt"),
                    courriel = o.optString("em")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(entries: List<LogEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("t", e.timeMs); put("s", e.satName); put("c", e.catnum)
                put("az", e.azimuthDeg); put("el", e.elevationDeg); put("n", e.note)
                put("ml", e.myLocator); put("cs", e.callsign); put("tl", e.theirLocator)
                put("mg", e.myGrids)
                put("md", e.mode); put("rs", e.rstSent); put("rr", e.rstRcvd)
                put("dl", e.downlinkMhz); put("ul", e.uplinkMhz)
                put("ev", e.envoyeMs)
                put("lo", e.locatorOrigine)
                put("nm", e.nom); put("qt", e.qth); put("em", e.courriel)
            })
        }
        runCatching { file.writeText(arr.toString()) }
    }

    fun add(entry: LogEntry): List<LogEntry> {
        val list = load().toMutableList()
        list.add(0, entry)   // newest first
        save(list)
        return list
    }

    fun update(
        timeMs: Long, callsign: String, theirLocator: String, note: String,
        mode: String, rstSent: String, rstRcvd: String
    ): List<LogEntry> {
        val list = load().map {
            if (it.timeMs == timeMs) it.copy(
                callsign = callsign, theirLocator = theirLocator, note = note,
                mode = mode, rstSent = rstSent, rstRcvd = rstRcvd) else it
        }
        save(list)
        return list
    }

    /**
     * Nomme une entrée en attente et la fait sortir de la file.
     *
     * [origine] dit d'où vient le carré, et n'est écrit qu'ici : c'est le seul
     * moment où l'information existe encore.
     */
    /**
     * Change le satellite d'un contact.
     *
     * Le nom est figé à la création, d'après le satellite alors sélectionné.
     * Quand il se trouve faux — sélection changée par mégarde, contact d'un
     * passage voisin — l'opérateur doit pouvoir le corriger : une entrée mal
     * attribuée fausse le carnet, l'ADIF et les carrés travaillés.
     */
    fun changeSatellite(
        timeMs: Long, satName: String, catnum: Int,
        az: Double? = null, el: Double? = null, nouvelleHeure: Long? = null,
    ): List<LogEntry> {
        val list = load().map {
            if (it.timeMs == timeMs) it.copy(
                satName = satName, catnum = catnum,
                // L'azimut et l'élévation appartiennent au couple
                // satellite + instant : changer l'un sans recalculer l'autre
                // laisserait des chiffres qui ne veulent plus rien dire.
                azimuthDeg = az ?: it.azimuthDeg,
                elevationDeg = el ?: it.elevationDeg,
                timeMs = nouvelleHeure ?: it.timeMs)
            else it
        }
        save(list)
        return list
    }

    fun nomme(
        timeMs: Long, callsign: String, theirLocator: String, origine: String,
        rstSent: String = "", rstRcvd: String = "",
    ): List<LogEntry> {
        val list = load().map {
            if (it.timeMs == timeMs) it.copy(
                callsign = callsign, theirLocator = theirLocator,
                locatorOrigine = origine,
                // Un champ vide ne détruit pas un report déjà saisi : nommer
                // et coter sont deux gestes qui peuvent venir dans les deux
                // ordres.
                rstSent = rstSent.ifBlank { it.rstSent },
                rstRcvd = rstRcvd.ifBlank { it.rstRcvd }) else it
        }
        save(list)
        return list
    }

    /**
     * Redate une entrée et remet à jour sa géométrie.
     *
     * L'heure d'un contact est sa clé : la changer suppose de réécrire
     * l'entrée. Et l'azimut comme l'élévation doivent suivre, sinon on
     * décrirait la position du satellite à un instant qui n'est plus celui du
     * contact — un instantané qui mentirait sur sa propre date.
     */
    fun redate(ancienMs: Long, nouveauMs: Long, azDeg: Double, elDeg: Double): List<LogEntry> {
        val list = load().map {
            if (it.timeMs == ancienMs)
                it.copy(timeMs = nouveauMs, azimuthDeg = azDeg, elevationDeg = elDeg)
            else it
        }.sortedByDescending { it.timeMs }
        save(list)
        return list
    }

    fun delete(timeMs: Long): List<LogEntry> {
        val list = load().filterNot { it.timeMs == timeMs }
        save(list)
        return list
    }

    /**
     * Marque un contact comme déposé au carnet en ligne.
     *
     * Écrit tout de suite plutôt qu'à la fin du lot : une coupure au milieu
     * laisse alors le travail déjà fait, au lieu de le refaire — et de créer
     * autant de doublons chez Wavelog, qui ne dédoublonne pas.
     */
    fun marqueEnvoye(timeMs: Long, quandMs: Long): List<LogEntry> {
        val list = load().map { if (it.timeMs == timeMs) it.copy(envoyeMs = quandMs) else it }
        save(list)
        return list
    }

    /**
     * ADIF de tout le carnet ; [station] part dans STATION_CALLSIGN.
     *
     * **Une entrée sans indicatif n'est pas un contact.** Un enregistrement
     * ADIF sans CALL n'est pas un trafic, c'est un trou : le carnet d'en face
     * le refuse ou le range de travers, et LoTW ne saura jamais quoi en faire.
     *
     * La règle portait autrefois sur un drapeau — l'entrée « à nommer » de la
     * file — et laissait donc passer les relevés anonymes posés d'un appui sur
     * la boussole. Quatre d'entre eux sont partis à l'export le 25 août. C'est
     * l'indicatif qui décide, et lui seul : il est le fait, le drapeau n'était
     * qu'une intention.
     */
    fun toAdif(station: String = ""): String =
        Adif.export(load().filter { it.callsign.isNotBlank() }, station)

    companion object {

        /**
         * ADIF d'une sélection d'entrées — carnet complet ou activation. La
         * fabrication elle-même vit dans [Adif], qui ne dépend pas d'Android
         * et se vérifie donc sur machine.
         */
        fun toAdif(entries: List<LogEntry>, station: String = ""): String =
            Adif.export(entries, station)
    }
}
