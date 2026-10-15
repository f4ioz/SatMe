/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import fr.f4ioz.satcombo.aprs.Paquet
import fr.f4ioz.satcombo.data.Observer
import org.json.JSONArray
import org.json.JSONObject

/**
 * The station page's APRS and FT8 tabs, written once: SatMe-PC and the
 * phone give what they heard; the answers are the same, in the same words.
 */
object ApiRadio {

    /**
     * APRS's game, as the phone's Trophies tab: today's tally, the records,
     * the badges (won or to win), the contacts made, the recent cheers.
     */
    fun trophees(e: fr.f4ioz.satcombo.aprs.Trophees.Etat, paquets: List<Paquet>, moi: String, obs: Observer?,
                 evenements: List<Pair<Long, fr.f4ioz.satcombo.aprs.Trophees.Evenement>>, maintenant: Long): JSONObject {
        val t = fr.f4ioz.satcombo.i18n.I18n::t
        val pays: (String) -> String? = { c -> fr.f4ioz.satcombo.domain.Dxcc.entite(fr.f4ioz.satcombo.aprs.AprsJeu.base(c))?.nom }
        val minuit = java.util.Calendar.getInstance().apply { timeInMillis = maintenant; set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
        val b = fr.f4ioz.satcombo.aprs.AprsJeu.bilan(paquets, minuit, moi, obs?.latDeg, obs?.lonDeg, pays)
        fun ev(x: fr.f4ioz.satcombo.aprs.Trophees.Evenement): String = when (x) {
            is fr.f4ioz.satcombo.aprs.Trophees.Evenement.RepeteIss -> "🔁 Ta trame est revenue par l'ISS" + (if (x.autres.isNotEmpty()) " (avec " + x.autres.take(5).joinToString(", ") + ")" else "")
            is fr.f4ioz.satcombo.aprs.Trophees.Evenement.NouveauCarre -> "🟩 Nouveau carré " + x.carre + " (" + x.indicatif + ")"
            is fr.f4ioz.satcombo.aprs.Trophees.Evenement.NouveauPays -> "🌍 Nouveau pays : " + x.pays + " (" + x.indicatif + ")"
            is fr.f4ioz.satcombo.aprs.Trophees.Evenement.Record -> "📏 Record : " + x.km + " km (" + x.indicatif + ")"
            is fr.f4ioz.satcombo.aprs.Trophees.Evenement.Contact -> "🤝 Contact avec " + x.contact.indicatif + (if (x.contact.viaIss) " par l'ISS" else "")
            is fr.f4ioz.satcombo.aprs.Trophees.Evenement.NouveauBadge -> x.badge.emoji + " Trophée : " + t("aprs_badge_" + x.badge.cle)
        }
        return JSONObject()
            .put("jour", JSONObject().put("stations", b.stations).put("pays", b.pays.size).put("carres", b.carres.size)
                .put("plusLoin", b.plusLoin?.let { s -> JSONObject().put("call", s.indicatif).put("km", Math.round(s.km ?: 0.0)) } ?: JSONObject.NULL)
                .put("viaIss", b.viaIss).put("envoyes", b.envoyes).put("accuses", b.accuses).put("contacts", b.contacts).put("repetes", b.repetes))
            .put("records", JSONObject().put("km", Math.round(e.recordKm)).put("kmCall", e.recordIndicatif).put("kmQuand", e.recordQuand)
                .put("carres", e.carres.size).put("pays", e.pays.size).put("repetes", e.repetesIss).put("contacts", e.contacts.size))
            .put("badges", JSONArray(fr.f4ioz.satcombo.aprs.Trophees.Badge.entries.map { x ->
                JSONObject().put("cle", x.cle).put("emoji", x.emoji).put("nom", t("aprs_badge_" + x.cle)).put("desc", t("aprs_badge_" + x.cle + "_desc"))
                    .put("quand", e.badges[x] ?: JSONObject.NULL) }))
            .put("contacts", JSONArray(fr.f4ioz.satcombo.aprs.AprsJeu.contacts(paquets, moi).sortedByDescending { it.quand }.take(50).map { c ->
                JSONObject().put("call", c.indicatif).put("quand", c.quand).put("carre", c.carre ?: "").put("iss", c.viaIss) }))
            .put("evenements", JSONArray(evenements.take(20).map { (q, x) -> JSONObject().put("quand", q).put("texte", ev(x)) }))
    }

    /** APRS heard ([paquets], newest first) and what they add up to: stations, how far, via the ISS. */
    fun aprs(paquets: List<Paquet>, actif: Boolean, recues: Int, obs: Observer?): JSONObject {
        val stations = fr.f4ioz.satcombo.aprs.AprsJeu.stations(paquets, obs?.latDeg, obs?.lonDeg)
        val km = stations.associateBy { it.indicatif }
        return JSONObject().put("present", true).put("actif", actif).put("recues", recues)
            .put("nbStations", stations.size).put("viaIss", paquets.count { it.viaIss })
            .put("loin", stations.mapNotNull { it.km }.maxOrNull() ?: JSONObject.NULL)
            .put("paquets", JSONArray(paquets.take(400).map { p ->
                val stn = km[p.nom ?: p.source]
                JSONObject().put("t", p.quand).put("src", p.source).put("type", p.type.name).put("iss", p.viaIss)
                    .put("dest", p.destinataire ?: "").put("txt", (p.message ?: p.commentaire).take(120))
                    .put("chemin", p.trame.relais.joinToString(",") { it.toString() + if (it.repete) "*" else "" })
                    .put("carre", stn?.carre ?: "").put("km", stn?.km?.let { Math.round(it) } ?: JSONObject.NULL)
            }))
    }

    /** One FT8/FT4 decode: its slot, its text, who calls whom, the square given. */
    class Ligne(val tranche: Long, val texte: String, val appelant: String?, val appele: String?, val locator: String?,
                val frequenceHz: Int, val rapportDb: Int, val decalageS: Double, val mode: String)

    private val CARRE = Regex("^[A-R]{2}[0-9]{2}$")

    /**
     * Who calls whom in a standard message, from its text alone ("CQ DX
     * F4IOZ JN18", "F4ABC F4IOZ -10"): caller, called (null for a CQ), the
     * square when one ends it.
     */
    fun decoupe(texte: String): Triple<String?, String?, String?> {
        val t = texte.trim().split(Regex("\\s+")).map { it.removePrefix("<").removeSuffix(">") }.filter { it.isNotEmpty() }
        if (t.isEmpty()) return Triple(null, null, null)
        val carre = t.last().takeIf { t.size >= 3 && CARRE.matches(it) && it != "RR73" }
        return if (t[0] == "CQ") {
            // "CQ DX F4IOZ JN18": the word after CQ is a direction when a callsign follows it.
            val reste = t.drop(1).let { r -> if (r.size >= 2 && (r[0].length <= 4 && r[0].all { it.isLetter() } || r[0].all { it.isDigit() })) r.drop(1) else r }
            Triple(reste.firstOrNull(), null, carre)
        } else Triple(t.getOrNull(1), t[0], carre)
    }

    /** FT8 heard: the decodes with what they mean here (CQ, for me, how far, already worked), the waterfall. */
    fun ft8(enMarche: Boolean, mode: String, avancement: Float, niveau: Float, tranches: Int, carte: Boolean, basseHz: Int, hauteHz: Int,
            cascade: List<ByteArray>, spectre: FloatArray, entendus: List<Ligne>, moi: String, obs: Observer?, faits: Set<String>,
            /** The log's countries and squares (4 characters): a station from elsewhere is new. */
            paysFaits: Set<String> = emptySet(), carresFaits: Set<String> = emptySet()): JSONObject {
        val compte = entendus.mapNotNull { it.appelant?.uppercase() }.groupingBy { it }.eachCount()
        return JSONObject().put("present", true).put("enMarche", enMarche).put("mode", mode).put("avancement", avancement.toDouble())
            .put("niveau", Math.round(niveau * 1000.0) / 1000.0).put("tranches", tranches).put("carte", carte)
            .put("basse", basseHz).put("haute", hauteHz)
            .put("cascade", JSONArray(cascade.map { java.util.Base64.getEncoder().encodeToString(it) }))
            .put("spectre", JSONArray(spectre.map { Math.round(it * 100) / 100.0 }))
            .put("entendus", JSONArray(entendus.take(400).map { e ->
                val ll = e.locator?.let { fr.f4ioz.satcombo.location.Maidenhead.centre(it) }
                JSONObject().put("t", e.tranche).put("texte", e.texte).put("de", e.appelant ?: "").put("a", e.appele ?: "")
                    .put("loc", e.locator ?: "").put("hz", e.frequenceHz).put("db", e.rapportDb).put("dt", Math.round(e.decalageS * 10) / 10.0)
                    .put("mode", e.mode).put("cq", e.texte.startsWith("CQ"))
                    .put("moi", moi.isNotBlank() && e.appele?.uppercase() == moi)
                    .put("fait", e.appelant?.uppercase()?.substringBefore('/') in faits)
                    .put("km", if (ll != null && obs != null) Math.round(fr.f4ioz.satcombo.sonde.Geo.distanceKm(obs.latDeg, obs.lonDeg, ll.first, ll.second)) else JSONObject.NULL)
                    .put("az", if (ll != null && obs != null) Math.round(fr.f4ioz.satcombo.sonde.Geo.bearingDeg(obs.latDeg, obs.lonDeg, ll.first, ll.second)) else JSONObject.NULL)
                    .also { o ->
                        // Where it is from (its country, flag, continent), and what it would bring the log.
                        val ent = e.appelant?.let { fr.f4ioz.satcombo.domain.Dxcc.entite(it) }
                        o.put("pays", ent?.nom ?: "").put("drapeau", ent?.drapeau ?: "").put("cont", ent?.continent ?: "")
                        o.put("nouveauPays", ent != null && paysFaits.isNotEmpty() && ent.nom !in paysFaits)
                        o.put("nouveauCarre", e.locator != null && e.locator.length >= 4 && carresFaits.isNotEmpty() && e.locator.take(4).uppercase() !in carresFaits)
                        o.put("vu", compte[e.appelant?.uppercase()] ?: 0)
                        // Which slot of the two it sends in (an FT8 minute has four: even and odd).
                        o.put("pair", (e.tranche / (if (e.mode == "FT4") 7_500L else 15_000L)) % 2 == 0L)
                    }
            }))
    }

    /** The waterfall's rows after the page's last ([depuis]), oldest first, with what dates them. */
    fun ft8Cascade(c: fr.f4ioz.satcombo.domain.Ft8Cascade?, depuis: Long, derniereLigneMs: Long): JSONObject {
        if (c == null) return JSONObject().put("total", 0)
        val l = c.depuis(depuis)
        return JSONObject().put("total", c.total).put("colonnes", c.colonnes).put("pasMs", c.pasMs).put("finMs", derniereLigneMs)
            .put("lignes", JSONArray(l.map { java.util.Base64.getEncoder().encodeToString(it) }))
    }

    /**
     * The KISS radio as the APRS tab shows it: connected, in KISS, its
     * frequency, what it received; [ports] the serial ports to choose from,
     * [port] the one kept; the working mode and the Doppler.
     */
    fun kiss(e: fr.f4ioz.satcombo.aprs.LienKiss.Etat, ports: List<Pair<Int, String>>, port: Int?, travail: String, doppler: Boolean,
             lignesLevees: Boolean, lignesAuChoix: Boolean, voulue: Long?): JSONObject =
        JSONObject().put("connecte", e.connecte).put("nom", e.nom).put("vitesse", e.vitesse).put("initialise", e.initialise)
            .put("recues", e.recues).put("envoyees", e.envoyees).put("erreur", e.erreur ?: "").put("muette", e.muette)
            .put("frequence", e.frequenceHz ?: JSONObject.NULL).put("bande", e.bande ?: JSONObject.NULL)
            .put("ports", JSONArray(ports.map { (i, l) -> JSONObject().put("index", i).put("libelle", l) }))
            .put("port", port ?: JSONObject.NULL).put("travail", travail).put("doppler", doppler)
            .put("lignes", lignesLevees).put("lignesAuChoix", lignesAuChoix).put("voulue", voulue ?: JSONObject.NULL)

    /** What the KISS card shows for sending: who, by which path now, the last result, the options. */
    fun kissEmission(o: JSONObject, source: String, chemin: List<String>, envoi: String, balise: Boolean, floue: Boolean, ssid: Int): JSONObject =
        o.put("source", source).put("chemin", chemin.joinToString(",")).put("envoi", envoi)
            .put("balise", balise).put("floue", floue).put("ssid", ssid)
            .put("fenetres", JSONArray(fr.f4ioz.satcombo.aprs.AprsEmission.FENETRES.map { JSONArray(listOf(it.first, it.last)) }))
}
