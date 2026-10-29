/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.datv.BalisesQo100
import fr.f4ioz.satcombo.datv.BandeLarge
import fr.f4ioz.satcombo.datv.Datv
import fr.f4ioz.satcombo.datv.EtatDatv
import fr.f4ioz.satcombo.datv.NomsSignaux
import fr.f4ioz.satcombo.datv.ReglageDatv
import fr.f4ioz.satcombo.datv.SecoursDatv
import fr.f4ioz.satcombo.plateforme.Stockage
import org.json.JSONArray
import org.json.JSONObject

/**
 * « Balayage auto » in the DATV tab (off unless chosen): [SecoursDatv] fed
 * each second with the receiver's state ([DatvPc], here or through a
 * SatMe-Node) and the candidates — the favourites ★, the beacon, the
 * stations on the ground station's spectrum ([SpectreLargePc]) — and the
 * receiver tuned where it says ([recois], [DatvPc.demarre]: LongMynd started
 * again on the new frequency, a few seconds).
 *
 * A tuning by hand reaches it through [manuel] (the DATV tab's « Démarrer »,
 * a click on the spectrum, a tune by UDP); the « veille », a recording or the
 * beacons' check hold it ([bloque]).
 */
class SecoursDatvPc(
    private val stockage: Stockage,
    private val datv: DatvPc,
    private val large: SpectreLargePc,
    private val horloge: () -> Long = System::currentTimeMillis,
    private val recois: (ReglageDatv) -> String? = { datv.demarre(it) },
) {
    /** Another reason to leave the receiver alone now (the beacons' check), or null. */
    @Volatile var bloque: () -> String? = { null }

    val machine = SecoursDatv(lisReglages())

    private fun lisReglages() = SecoursDatv.Reglages(
        actif = stockage.getBoolean("datv_secours", false),
        mode = SecoursDatv.Mode.de(stockage.getString("datv_secours_mode", null)),
        dureeS = stockage.getInt("datv_secours_duree", 30), attenteS = stockage.getInt("datv_secours_attente", 15),
        pauseMin = stockage.getInt("datv_secours_pause", 10), retour = stockage.getBoolean("datv_secours_retour", true),
        balise = stockage.getBoolean("datv_secours_balise", true)).borne()

    private fun garde(r: SecoursDatv.Reglages) {
        stockage.edit().putBoolean("datv_secours", r.actif).putString("datv_secours_mode", r.mode.cle).putInt("datv_secours_duree", r.dureeS)
            .putInt("datv_secours_attente", r.attenteS).putInt("datv_secours_pause", r.pauseMin).putBoolean("datv_secours_retour", r.retour)
            .putBoolean("datv_secours_balise", r.balise).apply()
    }

    val on: Boolean get() = machine.reglages.actif

    // ------------------------------------------------------------------ the favourites

    /** The favourites ★ (frequency, rate, name), in their order. */
    var favoris: List<SecoursDatv.Cible>
        get() = favorisDe(stockage.getString("datv_secours_favoris", null))
        private set(v) { stockage.edit().putString("datv_secours_favoris", JSONArray(v.take(MAX_FAVORIS).map {
            JSONObject().put("k", it.khz).put("sr", it.srKs).put("n", it.nom.take(40)) }).toString()).apply() }

    /** ★ on (added last) or off for [c]. */
    fun favori(c: SecoursDatv.Cible, on: Boolean) {
        val l = favoris.filter { !it.meme(c) }
        favoris = if (on) l + c else l
    }

    // ------------------------------------------------------------------ each second

    /** The operator tuned [r] by hand (or stopped: [r] null): the scan paused, that signal followed. */
    fun manuel(r: ReglageDatv?) {
        if (r != null) machine.manuel(horloge(), SecoursDatv.Cible(r.cielKhz, r.srKs, nomDe(r.cielKhz, r.srKs)))
    }

    fun tic() {
        val t = horloge()
        if (!on) return
        val e = datv.etat
        val r = datv.reglage
        val frais = large.on && large.frais()
        val raison = when {
            large.veille.active -> "la veille du spectre mène"
            e.enregistrement.isNotEmpty() -> "enregistrement en cours"
            else -> runCatching { bloque() }.getOrNull()
        }
        val a = machine.tic(t, SecoursDatv.Reception(e.actif, e.verrouille, sain(e, t), r.cielKhz, r.srKs), candidats(t, frais), frais, raison) ?: return
        val err = recois(r.copy(cielKhz = a.cible.khz, srKs = a.cible.srKs))
        if (err == null) machine.accorde(horloge(), a) else machine.refuse(horloge(), a, err)
    }

    /**
     * A good reception: locked, the quality not in the red (frames or packets lost) and pictures made — counted
     * once the lock is 10 s old (the decoder needs a picture with its headers first).
     */
    private fun sain(e: EtatDatv, t: Long): Boolean {
        if (!e.actif || !e.verrouille) return false
        val q = datv.qualite() ?: return true
        if (q.optJSONObject("verdict")?.optInt("niveau") == 2) return false
        val images = if (q.isNull("imagesS")) null else q.optDouble("imagesS")
        return !(images != null && images < 0.1 && t - datv.verrouDepuisMs > 10_000L)
    }

    private fun nomDe(khz: Long, sr: Int): String =
        if (Math.abs(khz - Datv.BALISE_KHZ) <= 100) "balise" else large.noms.nom(khz, sr, horloge()) ?: favoris.firstOrNull { it.meme(khz, sr) }?.nom.orEmpty()

    /** The candidates now: the favourites, the beacon, the signals on a fresh spectrum (with their names, levels, when received well). */
    fun candidats(t: Long, frais: Boolean): List<SecoursDatv.Candidat> {
        val sig = if (frais) large.signaux else emptyList()
        fun vu(c: SecoursDatv.Cible) = sig.filter { !it.balise && SpectreLargePc.sur(it, c.khz) }
            .minByOrNull { Math.abs(it.centreKhz - c.khz) }
        val entendus = large.entendus.toutes()
        fun recent(khz: Long, sr: Int) = entendus.filter { it.srKs == sr && Math.abs(it.khz - khz) <= NomsSignaux.tolerance(sr) }.maxOfOrNull { it.dernierMs } ?: 0L
        val l = ArrayList<SecoursDatv.Candidat>()
        for (f in favoris) {
            val s = vu(f)
            l += SecoursDatv.Candidat(f, SecoursDatv.Origine.FAVORI, s?.niveau ?: 0, recent(f.khz, f.srKs), if (frais) s != null else null)
        }
        val b = sig.firstOrNull { it.balise }
        l += SecoursDatv.Candidat(SecoursDatv.Cible(Datv.BALISE_KHZ, BalisesQo100.SR_KS, "balise"), SecoursDatv.Origine.BALISE, b?.niveau ?: 0, 0L,
            if (frais) b != null else null)
        for (s in sig) {
            if (s.balise || s.srKs !in 25..4000) continue
            val nom = large.noms.nom(s.centreKhz, s.srKs, t).orEmpty()
            l += SecoursDatv.Candidat(SecoursDatv.Cible(s.centreKhz, s.srKs, nom), SecoursDatv.Origine.SPECTRE, s.niveau, recent(s.centreKhz, s.srKs), true)
        }
        return l
    }

    // ------------------------------------------------------------------ the page

    fun json(): JSONObject {
        val t = horloge()
        val f = favoris
        return machine.json(t).put("ok", true).put("spectre", large.on && large.frais())
            .put("favoris", JSONArray(f.map { JSONObject().put("khz", it.khz).put("sr", it.srKs).put("nom", it.nom).put("libelle", it.libelle) }))
            .also { o ->
                val c = o.optJSONArray("candidats") ?: JSONArray()
                for (i in 0 until c.length()) c.optJSONObject(i)?.let { k -> k.put("favori", f.any { it.meme(k.optLong("khz"), k.optInt("sr")) }) }
            }
    }

    fun action(q: Map<String, String>): JSONObject {
        var r = machine.reglages
        q["mode"]?.let { r = r.copy(mode = SecoursDatv.Mode.de(it)) }
        q["duree"]?.trim()?.toIntOrNull()?.let { r = r.copy(dureeS = it) }
        q["attente"]?.trim()?.toIntOrNull()?.let { r = r.copy(attenteS = it) }
        q["pause"]?.trim()?.toIntOrNull()?.let { r = r.copy(pauseMin = it) }
        q["retour"]?.let { r = r.copy(retour = it == "1") }
        q["balise"]?.let { r = r.copy(balise = it == "1") }
        r = r.borne()
        machine.reglages = r.copy(actif = machine.reglages.actif)
        q["on"]?.let { machine.active(it == "1") }
        garde(machine.reglages)
        if (q["reprends"] == "1") machine.reprends()
        val khz = q["khz"]?.trim()?.toLongOrNull()
        val sr = q["sr"]?.trim()?.toIntOrNull()
        if (q["favori"] != null) {
            // ★ on what is given, else on what is received now.
            val c = if (khz != null && sr != null) SecoursDatv.Cible(khz, sr, q["nom"]?.trim().orEmpty().ifEmpty { nomDe(khz, sr) })
                else datv.reglage.let { SecoursDatv.Cible(it.cielKhz, it.srKs, nomDe(it.cielKhz, it.srKs)) }
            if (c.khz !in BandeLarge.DEBUT_KHZ..BandeLarge.DEBUT_KHZ + BandeLarge.LARGEUR_KHZ || c.srKs !in 25..4000)
                return JSONObject().put("ok", false).put("erreur", "signal hors de la bande large")
            favori(c, q["favori"] == "1")
        }
        return JSONObject().put("ok", true)
    }

    companion object {
        const val MAX_FAVORIS = 12

        fun favorisDe(s: String?): List<SecoursDatv.Cible> {
            val a = runCatching { JSONArray(s ?: return emptyList()) }.getOrNull() ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { SecoursDatv.Cible(it.optLong("k"), it.optInt("sr"), it.optString("n")) }
                .filter { it.khz > 0 && it.srKs > 0 }
        }
    }
}
