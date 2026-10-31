/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import fr.f4ioz.satcombo.domain.JournalPassage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Which contacts are confirmed — eQSL, LoTW, paper card — as the online log
 * (Wavelog, Cloudlog) knows it.
 *
 * Wavelog's `get_contacts_adif` carries, for each contact, LOTW_QSL_RCVD,
 * EQSL_QSL_RCVD, QSL_RCVD (Y, N, R, I, V) and their *_SENT twins. SatMe keeps
 * only those flags, keyed by callsign, time and satellite, and lays them on
 * its own contacts. **This reading never logs into eQSL or LoTW itself**: that
 * would mean storing those passwords. Wavelog holds them already, and its own sync
 * brings the confirmations in.
 */
object StatutsQsl {

    /** One contact's confirmation flags, as the online log has them. */
    data class Statut(
        val indicatif: String, val quandMs: Long, val satellite: String,
        val lotwRecu: String = "", val lotwEnvoye: String = "",
        val eqslRecu: String = "", val eqslEnvoye: String = "",
        val papierRecu: String = "", val papierEnvoye: String = "",
    ) {
        val cle: String get() = cle(indicatif, quandMs, satellite)
    }

    fun cle(indicatif: String, quandMs: Long, satellite: String) =
        indicatif.trim().uppercase() + "|" + quandMs + "|" + satellite.trim().uppercase()

    /** Where a contact stands with one service. */
    enum class Etat { CONFIRME, ATTENTE, AUCUN }

    /**
     * Y (and the older V, "verified") is a confirmation. Sent (Y, Q queued,
     * R requested) without one is waiting. I ("ignore") and nothing at all:
     * the service is not used for this contact, no pill.
     */
    fun etat(recu: String, envoye: String): Etat {
        val r = recu.trim().uppercase(); val e = envoye.trim().uppercase()
        return when {
            r == "Y" || r == "V" -> Etat.CONFIRME
            r == "I" || e == "I" -> Etat.AUCUN
            e == "Y" || e == "Q" || e == "R" || r == "R" -> Etat.ATTENTE
            else -> Etat.AUCUN
        }
    }

    fun lotw(s: Statut) = etat(s.lotwRecu, s.lotwEnvoye)
    fun eqsl(s: Statut) = etat(s.eqslRecu, s.eqslEnvoye)
    fun papier(s: Statut) = etat(s.papierRecu, s.papierEnvoye)

    /** The flags of every satellite contact of an ADIF (those with a callsign, a date and a SAT_NAME or PROP_MODE=SAT). */
    fun lis(texte: String): List<Statut> {
        val corps = texte.split(Regex("<EOH>", RegexOption.IGNORE_CASE), 2).let { if (it.size == 2) it[1] else texte }
        val out = ArrayList<Statut>()
        corps.split(Regex("<EOR>", RegexOption.IGNORE_CASE)).forEach { bloc ->
            if (!bloc.contains('<')) return@forEach
            val f = AdifImport.champs(bloc)
            val sat = f["SAT_NAME"].orEmpty().trim()
            if (sat.isEmpty() && !f["PROP_MODE"].orEmpty().equals("SAT", true)) return@forEach
            val call = f["CALL"].orEmpty().trim().uppercase()
            if (call.isEmpty()) return@forEach
            val quand = AdifImport.instant(f["QSO_DATE"].orEmpty(), f["TIME_ON"].orEmpty()) ?: return@forEach
            fun v(k: String) = f[k].orEmpty().trim().uppercase().take(1)
            out += Statut(call, quand, sat,
                lotwRecu = v("LOTW_QSL_RCVD"), lotwEnvoye = v("LOTW_QSL_SENT"),
                eqslRecu = v("EQSL_QSL_RCVD"), eqslEnvoye = v("EQSL_QSL_SENT"),
                papierRecu = v("QSL_RCVD"), papierEnvoye = v("QSL_SENT"))
        }
        return out
    }

    /** How far apart the online log's time and ours may be and still be the same contact. */
    const val ECART_MS = 5 * 60_000L

    /**
     * Each local contact's flags: same station (callsign without its "/P"),
     * same satellite (names compared on their short form), times within
     * [ECART_MS]; the closest in time wins, and an online contact serves one
     * local contact at most. Contacts with no match are left out.
     */
    fun associe(locaux: List<LogEntry>, statuts: List<Statut>): Map<Long, Statut> {
        val parIndicatif = statuts.groupBy { base(it.indicatif) }
        val pris = HashSet<String>()
        val out = HashMap<Long, Statut>()
        // Pairs closest first, so a near match is never stolen by a far one.
        val paires = ArrayList<Triple<Long, LogEntry, Statut>>()
        locaux.filter { it.callsign.isNotBlank() }.forEach { e ->
            parIndicatif[base(e.callsign)].orEmpty().forEach { s ->
                val dt = kotlin.math.abs(s.quandMs - e.timeMs)
                if (dt <= ECART_MS && (s.satellite.isBlank() || e.satName.isBlank() ||
                        s.satellite.equals(e.satName, true) || JournalPassage.memeSatellite(s.satellite, e.satName) ||
                        fr.f4ioz.satcombo.domain.NomSatellite.propre(e.satName).equals(s.satellite, true) ||
                        // Sent as « ARISS » (SatMe's SAT_NAME for the space station), kept here as « ISS ».
                        fr.f4ioz.satcombo.domain.NomSatellite.satNameAdif(e.satName).equals(s.satellite, true)))
                    paires += Triple(dt, e, s)
            }
        }
        paires.sortedBy { it.first }.forEach { (_, e, s) ->
            if (e.timeMs in out || s.cle in pris) return@forEach
            out[e.timeMs] = s; pris += s.cle
        }
        return out
    }

    /** "F4IOZ/P" and "EA8/F4IOZ" are F4IOZ for matching: the longest part with a digit. */
    fun base(indicatif: String): String {
        val c = JournalPassage.indicatifDeBase(indicatif)
        if (!c.contains('/')) return c
        return c.split('/').filter { p -> p.any { it.isDigit() } && p.any { it.isLetter() } }.maxByOrNull { it.length } ?: c
    }

    /** Confirmed and waiting, per service, over the local contacts matched. */
    data class Bilan(
        val releves: Int, val sansStatut: Int,
        val lotwConfirmes: Int, val lotwAttente: Int,
        val eqslConfirmes: Int, val eqslAttente: Int,
        val papierConfirmes: Int, val papierAttente: Int,
    )

    fun bilan(locaux: List<LogEntry>, associes: Map<Long, Statut>): Bilan {
        val l = associes.values
        return Bilan(l.size, locaux.count { it.callsign.isNotBlank() && it.timeMs !in associes },
            l.count { lotw(it) == Etat.CONFIRME }, l.count { lotw(it) == Etat.ATTENTE },
            l.count { eqsl(it) == Etat.CONFIRME }, l.count { eqsl(it) == Etat.ATTENTE },
            l.count { papier(it) == Etat.CONFIRME }, l.count { papier(it) == Etat.ATTENTE })
    }

    /** One contact's pills for the page: "ok" or "attente" per service, absent when unused. */
    fun json(s: Statut): JSONObject = JSONObject().also { o ->
        listOf("lotw" to lotw(s), "eqsl" to eqsl(s), "papier" to papier(s)).forEach { (k, e) ->
            when (e) { Etat.CONFIRME -> o.put(k, "ok"); Etat.ATTENTE -> o.put(k, "attente"); Etat.AUCUN -> {} }
        }
    }

    fun json(b: Bilan): JSONObject = JSONObject().put("releves", b.releves).put("sansStatut", b.sansStatut)
        .put("lotw", JSONObject().put("ok", b.lotwConfirmes).put("attente", b.lotwAttente))
        .put("eqsl", JSONObject().put("ok", b.eqslConfirmes).put("attente", b.eqslAttente))
        .put("papier", JSONObject().put("ok", b.papierConfirmes).put("attente", b.papierAttente))

    /**
     * The flags kept on disk (`qsl_statuts.json`), with the harvest cursor of
     * each station location and when the whole log was last read again.
     *
     * Incremental like the keypad's harvest: `fetchfromid` brings only the
     * contacts added since. But a confirmation arrives **on an old contact**,
     * which an incremental read never sees again: hence the full re-read,
     * asked by hand or when the last one is more than a day old.
     */
    class Store(private val file: java.io.File?) {
        @Volatile private var memoire: Etat3? = null

        private data class Etat3(val statuts: Map<String, Statut>, val curseurs: Map<String, Long>, val completMs: Long, val releveMs: Long)

        private fun etat(): Etat3 {
            memoire?.let { return it }
            val e = runCatching {
                val o = JSONObject(file!!.readText())
                val st = HashMap<String, Statut>()
                val a = o.optJSONArray("s") ?: JSONArray()
                for (i in 0 until a.length()) {
                    val x = a.getJSONObject(i)
                    val s = Statut(x.optString("c"), x.optLong("t"), x.optString("sat"),
                        x.optString("lr"), x.optString("le"), x.optString("er"), x.optString("ee"),
                        x.optString("pr"), x.optString("pe"))
                    st[s.cle] = s
                }
                val cu = HashMap<String, Long>()
                o.optJSONObject("curseurs")?.let { c -> c.keys().forEach { k -> cu[k] = c.optLong(k) } }
                Etat3(st, cu, o.optLong("complet"), o.optLong("releve"))
            }.getOrElse { Etat3(emptyMap(), emptyMap(), 0L, 0L) }
            memoire = e
            return e
        }

        fun statuts(): List<Statut> = etat().statuts.values.toList()
        fun curseur(profil: String): Long = etat().curseurs[profil] ?: 0L
        /** When the whole online log was last read (0: never). */
        fun completMs(): Long = etat().completMs
        /** When anything was last read (0: never). */
        fun releveMs(): Long = etat().releveMs

        /**
         * New flags in: an entry already known is replaced (its confirmation
         * may have changed). [complet]: this was a full read.
         */
        @Synchronized
        fun fusionne(nouveaux: List<Statut>, curseurs: Map<String, Long>, complet: Boolean, maintenant: Long) {
            val e = etat()
            val st = HashMap(e.statuts); nouveaux.forEach { st[it.cle] = it }
            val n = Etat3(st, e.curseurs + curseurs, if (complet) maintenant else e.completMs, maintenant)
            memoire = n
            val f = file ?: return
            val o = JSONObject().put("complet", n.completMs).put("releve", n.releveMs)
                .put("curseurs", JSONObject(n.curseurs as Map<*, *>))
                .put("s", JSONArray(n.statuts.values.map { s ->
                    JSONObject().put("c", s.indicatif).put("t", s.quandMs).put("sat", s.satellite)
                        .put("lr", s.lotwRecu).put("le", s.lotwEnvoye).put("er", s.eqslRecu).put("ee", s.eqslEnvoye)
                        .put("pr", s.papierRecu).put("pe", s.papierEnvoye)
                }))
            runCatching { f.parentFile?.mkdirs(); f.writeText(o.toString()) }
        }
    }
}
