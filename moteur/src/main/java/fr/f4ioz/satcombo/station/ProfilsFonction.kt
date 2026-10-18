/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import fr.f4ioz.satcombo.station.FonctionsMontage.Instantane
import org.json.JSONArray
import org.json.JSONObject

/**
 * « Profils de fonction »: the station's receive settings kept under a name
 * (« SSTV par IC-9700 », « QO-100 Pluto + FT8 »…) and put back in a safe
 * order. Also the one-click gestures of the « Montage » page, each one a
 * single step of the same kind.
 *
 * **Receive only.** The keys are a closed list ([CLES]): no transmission,
 * no transmitter setting, no LNB supply, no serial port can be reached from
 * here. Nothing is applied while a transmission is on.
 */
object ProfilsFonction {

    /** The settings a profile keeps, in the order they are put back (stops, settings, receivers, sound input, decoders). */
    val CLES: List<String> = listOf(
        "sdr.recepteur", "sdr.mode", "sdr.mhz", "sdr.suit", "sdr.son", "sdr.actif",
        "datv.recepteur", "datv.actif",
        "son.entree",
        "sstv.source", "sstv.actif", "ft8.mode", "ft8.actif", "cw.actif", "aprs.actif", "phonie.source", "phonie.actif",
    )

    /** Settings that may be applied, with what each takes. */
    private fun valide(cle: String, v: String): Boolean = when (cle) {
        "sdr.recepteur" -> v in setOf("rtl", "pluto", "bladerf")
        "sdr.mode" -> v.matches(Regex("[A-Z0-9_]{1,12}"))
        "sdr.mhz" -> v.replace(',', '.').toDoubleOrNull()?.let { it in 0.5..30000.0 } == true
        "datv.recepteur" -> v in setOf("minitiouner", "pluto", "bladerf")
        "son.entree" -> v.length <= 120 && v.none { it < ' ' }
        "sstv.source" -> v in setOf("auto", "carte", "sdr")
        "phonie.source" -> v in setOf("auto", "carte", "sdr", "datv")
        "ft8.mode" -> v in setOf("FT8", "FT4")
        "sdr.suit", "sdr.son", "sdr.actif", "datv.actif", "sstv.actif", "ft8.actif", "cw.actif", "aprs.actif", "phonie.actif" -> v == "0" || v == "1"
        else -> false
    }

    /** Whether [cle] = [valeur] may be done from the page (a profile's step or a shortcut). */
    fun admis(cle: String, valeur: String): Boolean = cle in CLES && valide(cle, valeur)

    data class Profil(val id: String, val nom: String, val reglages: Map<String, String>, val creeMs: Long = 0L)

    /** The receive settings now, as a profile keeps them. */
    fun capture(i: Instantane): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        fun b(x: Boolean) = if (x) "1" else "0"
        if (i.sdrPresent) {
            m["sdr.recepteur"] = i.sdrRecepteur
            if (i.sdrMode.isNotBlank()) m["sdr.mode"] = i.sdrMode
            if (i.sdrManuelleHz > 0) m["sdr.mhz"] = String.format(java.util.Locale.US, "%.6f", i.sdrManuelleHz / 1e6)
            m["sdr.suit"] = b(i.sdrSuit)
            m["sdr.son"] = b(i.sdrHautParleur)
            // A METEOR pass or the radiosondes are not a plain reception: not kept as one.
            m["sdr.actif"] = b(i.sdrActif && !i.sdrPourMeteor && !i.sdrPourSonde)
        }
        if (i.datvPresent) { m["datv.recepteur"] = i.datvRecepteur; m["datv.actif"] = b(i.datvActif) }
        if (i.sonPresent) {
            m["son.entree"] = if (!i.sonActif) "" else if (i.sonParSdr) "sdr" else i.sonAppareil
            m["sstv.source"] = i.sstvSource; m["sstv.actif"] = b(i.sstvActif)
            m["ft8.mode"] = i.ft8Mode.ifBlank { "FT8" }; m["ft8.actif"] = b(i.ft8Actif)
            m["cw.actif"] = b(i.cwActif); m["aprs.actif"] = b(i.aprsActif)
        }
        if (i.phoniePresent) { m["phonie.source"] = i.phonieSource; m["phonie.actif"] = b(i.phonieActif) }
        return m
    }

    /** One step: [cle] from [avant] to [valeur]; [manuel] when SatMe leaves it to its tab (said why in [texte]). */
    data class Etape(val cle: String, val valeur: String, val avant: String, val texte: String, val manuel: Boolean = false) {
        fun json(): JSONObject = JSONObject().put("cle", cle).put("valeur", valeur).put("avant", avant).put("texte", texte).put("manuel", manuel)
    }

    private fun oui(v: String) = v == "1"
    private fun nomSource(v: String) = when (v) { "auto" -> "auto"; "carte" -> "carte son"; "sdr" -> "carte SDR"; "datv" -> "DATV"; else -> v }

    /** A step said in words. */
    fun dit(cle: String, v: String): String = when (cle) {
        "sdr.recepteur" -> "Récepteur SDR : " + FonctionsMontage.nomRessource("sdr:$v")
        "sdr.mode" -> "Récepteur SDR : mode $v"
        "sdr.mhz" -> "Récepteur SDR : " + (v.replace(',', '.').toDoubleOrNull()?.let {
            String.format(java.util.Locale.FRANCE, "%.6f", it).trimEnd('0').let { t -> if (t.substringAfter(',').length < 3) String.format(java.util.Locale.FRANCE, "%.3f", it) else t }
        } ?: v) + " MHz"
        "sdr.suit" -> "Récepteur SDR : " + if (oui(v)) "suit le satellite" else "fréquence fixe"
        "sdr.son" -> "Récepteur SDR : son " + if (oui(v)) "sur le haut-parleur du PC" else "coupé sur ce PC"
        "sdr.actif" -> if (oui(v)) "Démarrer le récepteur SDR" else "Arrêter le récepteur SDR"
        "datv.recepteur" -> "Récepteur DATV : " + FonctionsMontage.nomRessource(if (v == "minitiouner") "datv:minitiouner" else "sdr:$v")
        "datv.actif" -> if (oui(v)) "Démarrer la réception DATV" else "Arrêter la réception DATV"
        "son.entree" -> when (v) { "" -> "Arrêter la carte son"; "sdr" -> "Entrée des décodeurs : la carte SDR"; else -> "Carte son : « " + FonctionsMontage.court(v) + " »" }
        "sstv.source" -> "SSTV : source " + nomSource(v)
        "sstv.actif" -> "SSTV réception " + if (oui(v)) "activée" else "coupée"
        "ft8.mode" -> "FT8 / FT4 : mode $v"
        "ft8.actif" -> if (oui(v)) "Démarrer FT8 / FT4" else "Arrêter FT8 / FT4"
        "cw.actif" -> "Décodeur CW " + if (oui(v)) "activé" else "coupé"
        "aprs.actif" -> "APRS " + if (oui(v)) "activé" else "coupé"
        "phonie.source" -> "Phonie → texte : source " + nomSource(v)
        "phonie.actif" -> "Phonie → texte " + if (oui(v)) "activée" else "coupée"
        else -> "$cle = $v"
    }

    /**
     * What applying [voulu] to the state [i] changes, in a safe order: the
     * functions to stop first (they free a board or the sound input), then
     * the settings, the receivers, the sound input, the decoders last (FT8
     * needs the sound running). Settings already so are left out.
     */
    fun plan(voulu: Map<String, String>, i: Instantane): List<Etape> {
        val avant = capture(i)
        val v = voulu.filter { (k, x) -> admis(k, x) }
        fun change(k: String) = v[k] != null && v[k] != (avant[k] ?: "")
        val l = ArrayList<Etape>()
        fun etape(k: String, manuel: Boolean = false, texte: String? = null) {
            if (change(k)) l += Etape(k, v[k]!!, avant[k] ?: "", texte ?: dit(k, v[k]!!), manuel)
        }
        val arrets = listOf("ft8.actif", "cw.actif", "aprs.actif", "sstv.actif", "phonie.actif", "datv.actif", "sdr.actif")
        // 1. Stops (DATV on a MiniTiouner left to its tab: it feeds the LNB).
        for (k in arrets) if (v[k] == "0" && change(k)) {
            val mini = k == "datv.actif" && i.datvRecepteur == "minitiouner"
            etape(k, mini, if (mini) "Arrêter la réception DATV : dans l'onglet DATV (le MiniTiouner alimente le LNB)" else null)
        }
        // 2. Settings.
        for (k in listOf("sdr.recepteur", "sdr.mode", "sdr.mhz", "sdr.suit", "sdr.son", "datv.recepteur", "sstv.source", "phonie.source", "ft8.mode")) etape(k)
        // 3. Receivers (DATV is never started from here: the LNB supply is set in its tab).
        if (v["sdr.actif"] == "1") etape("sdr.actif")
        // A new receiver chosen while the SDR runs: started again on it.
        else if (change("sdr.recepteur") && i.sdrActif && v["sdr.actif"] != "0")
            l += Etape("sdr.actif", "1", "1", "Redémarrer le récepteur SDR sur " + FonctionsMontage.nomRessource("sdr:" + v["sdr.recepteur"]))
        if (v["datv.actif"] == "1") etape("datv.actif", true, "Démarrer la réception DATV : dans l'onglet DATV (l'alimentation du LNB s'y règle)")
        // 4. The sound input: after the SDR, which takes it when it starts.
        val entree = v["son.entree"]
        if (entree != null && (change("son.entree") || (entree.isNotEmpty() && entree != "sdr" && v["sdr.actif"] == "1" && change("sdr.actif"))))
            l += Etape("son.entree", entree, avant["son.entree"] ?: "", dit("son.entree", entree))
        // 5. The decoders.
        for (k in listOf("sstv.actif", "ft8.actif", "cw.actif", "aprs.actif", "phonie.actif")) if (v[k] == "1") etape(k)
        return l
    }

    /** Does the steps: [emission] says the transmission on now (or null), [fait] one setting (null when done, else why not). */
    interface Executant {
        fun emission(): String?
        fun fait(cle: String, valeur: String): String?
    }

    data class Resultat(val etape: Etape, val ok: Boolean, val message: String) {
        fun json(): JSONObject = etape.json().put("ok", ok).put("message", message)
    }

    data class Bilan(val refus: String?, val resultats: List<Resultat>) {
        val ok get() = refus == null && resultats.all { it.ok }
        fun json(): JSONObject = JSONObject().put("ok", refus == null).put("refus", refus ?: "").put("toutBon", ok)
            .put("etapes", JSONArray(resultats.map { it.json() }))
    }

    /**
     * The steps of [plan] done one by one through [ex]. Refused as a whole
     * while a transmission is on; stopped there if one starts meanwhile.
     * Manual steps are listed, never done. A step that fails is said and
     * the next ones go on (each stands alone).
     */
    fun applique(etapes: List<Etape>, ex: Executant): Bilan {
        ex.emission()?.let { return Bilan("Rien n'est changé : $it. Arrêter l'émission d'abord.", emptyList()) }
        val r = ArrayList<Resultat>()
        for (e in etapes) {
            if (e.manuel) { r += Resultat(e, true, "à faire dans son onglet"); continue }
            if (!admis(e.cle, e.valeur)) { r += Resultat(e, false, "réglage refusé"); continue }
            ex.emission()?.let { t -> return Bilan("Arrêté : $t", r) }
            val err = runCatching { ex.fait(e.cle, e.valeur) }.getOrElse { it.message ?: "erreur" }
            r += Resultat(e, err == null, err ?: "fait")
        }
        return Bilan(null, r)
    }

    // ------------------------------------------------------------------ the file

    const val MAX = 30

    fun ecrit(l: List<Profil>): JSONObject = JSONObject().put("v", 1).put("profils", JSONArray(l.map { p ->
        JSONObject().put("id", p.id).put("nom", p.nom).put("cree", p.creeMs).put("reglages", JSONObject(p.reglages as Map<*, *>))
    }))

    /** The profiles read back, cleaned (unknown or out-of-range settings dropped). */
    fun lit(texte: String?): List<Profil> = runCatching {
        val a = JSONObject(texte ?: "").optJSONArray("profils") ?: JSONArray()
        (0 until minOf(a.length(), MAX)).mapNotNull { k ->
            val o = a.optJSONObject(k) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { Montage.ID.matches(it) } ?: return@mapNotNull null
            val r = o.optJSONObject("reglages") ?: JSONObject()
            val m = LinkedHashMap<String, String>()
            for (c in CLES) if (r.has(c)) r.optString(c).takeIf { admis(c, it) }?.let { m[c] = it }
            Profil(id, propre(o.optString("nom")).ifBlank { id }, m, o.optLong("cree"))
        }.distinctBy { it.id }
    }.getOrDefault(emptyList())

    fun propre(s: String) = s.filter { it >= ' ' && it != '<' && it != '>' }.trim().take(60)

    /** A new profile's id from its name. */
    fun nouvelId(nom: String, l: List<Profil>): String {
        val base = java.text.Normalizer.normalize(nom.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24).ifBlank { "profil" }
        var id = base; var n = 2
        while (l.any { it.id == id }) id = (base.take(28) + "-" + n++)
        return id
    }

    /** A profile and what it would change now, for the page. */
    fun json(p: Profil, i: Instantane?): JSONObject = JSONObject().put("id", p.id).put("nom", p.nom).put("cree", p.creeMs)
        .put("reglages", JSONArray(p.reglages.map { (k, v) -> JSONObject().put("cle", k).put("valeur", v).put("texte", dit(k, v)) }))
        .put("apercu", if (i == null) JSONArray() else JSONArray(plan(p.reglages, i).map { it.json() }))
}
