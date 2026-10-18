/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.cw.DecodeurCw
import fr.f4ioz.satcombo.domain.DecodeursMultiples
import fr.f4ioz.satcombo.domain.DecodeursMultiples.Decodeur
import fr.f4ioz.satcombo.plateforme.Stockage
import fr.f4ioz.satcombo.sdr.CanauxSecondaires
import fr.f4ioz.satcombo.sdr.Dsp
import fr.f4ioz.satcombo.sdr.ModeCanal
import org.json.JSONArray
import org.json.JSONObject

/**
 * « Décodeurs multiples » in the QO-100 cockpit: up to four channels taken
 * from the receiver's IQ besides the main one, each with its decoder. Off by
 * default: then nothing here runs and the single decoder of the cockpit is
 * fed by the main receiver as before.
 *
 * - **FT8/FT4 and CW** get a decoder of their own per channel.
 * - **SSTV and voice to text** have one engine each (one picture, one helper
 *   process): a channel holding one feeds it in place of the main receiver.
 * - **« Écouter »** on a channel: its sound replaces the main one (speaker,
 *   browsers, Son tab); the others stay silent.
 *
 * Receive only: nothing here transmits.
 */
class CanauxPc(
    private val stockage: Stockage,
    private val sstv: SstvPc? = null,
    private val phonie: PhoniePc? = null,
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    /** The option, off by default. */
    var actif: Boolean
        get() = stockage.getBoolean(CLE_ON, false)
        set(v) { stockage.edit().putBoolean(CLE_ON, v).apply(); if (!v) synchronized(this) { postes.values.forEach { it.ferme() }; postes.clear() } }

    @Volatile var liste: List<DecodeursMultiples.Canal> = DecodeursMultiples.lit(stockage.getString(CLE_LISTE, "[]"))
        private set

    /** My callsign, for the FT8 decoders (what a QSO lets them expect). */
    @Volatile var indicatif: () -> String = { "" }

    /** A decoder turned on when a channel needs it (the SSTV decoder, voice to text when installed). */
    @Volatile var allume: (Decodeur) -> Unit = { d ->
        if (d == Decodeur.SSTV) sstv?.let { if (!it.actif) it.actif = true }
        if (d == Decodeur.PHONIE) phonie?.let { p -> if (!p.actif && runCatching { p.install.python(p.python) != null }.getOrDefault(false)) p.actif = true }
    }

    /** The signal processing, run by the receiver's thread. */
    val dsp = CanauxSecondaires()

    /** What one channel has decoded. */
    private inner class Poste(val canal: DecodeursMultiples.Canal) {
        val ft8: Ft8Pc? = if (canal.decodeur == Decodeur.FT8 || canal.decodeur == Decodeur.FT4)
            Ft8Pc(horloge).also { f ->
                f.indicatif = indicatif()
                f.demarre(canal.decodeur.nom)
                f.auDecode = { l -> l.firstOrNull()?.let { note(it.texte, l.size) } }
            } else null
        var cw: DecodeurCw? = null
        val texteCw = StringBuilder()
        @Volatile var dernier = ""
        @Volatile var dernierMs = 0L
        @Volatile var decodes = 0

        fun note(t: String, n: Int = 1) { dernier = t; dernierMs = horloge(); decodes += n }

        fun ecoute(pcm: ShortArray, n: Int) {
            when (canal.decodeur) {
                Decodeur.FT8, Decodeur.FT4 -> ft8?.ecoute(pcm, n, Dsp.AUDIO_RATE)
                Decodeur.CW -> {
                    val d = cw ?: DecodeurCw(Dsp.AUDIO_RATE, { s ->
                        synchronized(texteCw) {
                            if (s == " " && (texteCw.isEmpty() || texteCw.last() == ' ')) return@synchronized
                            texteCw.append(s)
                            if (texteCw.length > 300) texteCw.delete(0, texteCw.length - 300)
                            if (s != " ") { dernierMs = horloge(); decodes++ }
                            dernier = texteCw.toString().takeLast(60).trim()
                        }
                    }).also { cw = it }
                    d.ecoute(pcm, n)
                }
                Decodeur.SSTV -> sstv?.ecouteSdr(pcm, n, Dsp.AUDIO_RATE)
                Decodeur.PHONIE -> phonie?.ecouteSdr(pcm, n, Dsp.AUDIO_RATE, null)
                Decodeur.AUCUN -> {}
            }
        }

        fun ferme() { ft8?.ferme() }
    }

    private val postes = HashMap<Int, Poste>()

    // ------------------------------------------------------------ the receiver's side (its thread)

    /** Channels to run for a block, given the panorama's axis at the PLL (null: off, or not on QO-100). */
    fun cibles(axe: Pair<Double, Double>?): List<CanauxSecondaires.Cible> {
        if (axe == null || !actif) return emptyList()
        val sens = if (axe.second < 0) -1 else 1
        return liste.map { CanauxSecondaires.Cible(it.id, DecodeursMultiples.decalage(it.cadranHz, axe.first, sens), it.mode) }
    }

    private var ecouteBuf = ShortArray(0)
    private var ecouteN = 0

    /** A channel's audio for this block ([Dsp.AUDIO_RATE]). */
    fun recoit(id: Int, pcm: ShortArray, n: Int) {
        val c = liste.firstOrNull { it.id == id } ?: return
        val p = synchronized(this) { postes[id] ?: Poste(c).also { postes[id] = it } }
        runCatching { p.ecoute(pcm, n) }
        if (c.ecoute) {
            if (ecouteBuf.size < ecouteN + n) ecouteBuf = ecouteBuf.copyOf(ecouteN + n)
            System.arraycopy(pcm, 0, ecouteBuf, ecouteN, n); ecouteN += n
        }
    }

    /**
     * After the block: the sound of the channel heard instead of the main one
     * (its buffer, its count), or null when none is. Taken once.
     */
    fun prendEcoute(): Pair<ShortArray, Int>? {
        if (ecouteN == 0) return null
        val r = ecouteBuf to ecouteN
        ecouteN = 0
        return r
    }

    /** True while a channel feeds the SSTV decoder: the main receiver then does not. */
    fun tientSstv(): Boolean = actif && liste.any { it.decodeur == Decodeur.SSTV }
    /** True while a channel feeds voice to text: the main receiver then does not. */
    fun tientPhonie(): Boolean = actif && liste.any { it.decodeur == Decodeur.PHONIE }
    /** True while a channel replaces the main sound. */
    fun tientSon(): Boolean = actif && liste.any { it.ecoute }
    /** The dial of the voice channel (for the transcript's frequency), or null. */
    fun cadranPhonie(): Long? = liste.firstOrNull { it.decodeur == Decodeur.PHONIE }?.cadranHz?.takeIf { actif && tourne }

    /** Set by the receiver each block: the channels run now (the receiver on QO-100, the option on, some channel). */
    @Volatile var tourne = false

    /** The SSTV channel's dial, or null. */
    fun cadranSstv(): Long? = liste.firstOrNull { it.decodeur == Decodeur.SSTV }?.cadranHz

    /** The SSTV automatic tuning moves the SSTV channel's dial by [hz]; its decoder keeps the picture under way. */
    fun decaleSstv(hz: Int) {
        val c = liste.firstOrNull { it.decodeur == Decodeur.SSTV } ?: return
        liste = liste.map { if (it.id == c.id) it.copy(cadranHz = it.cadranHz + hz) else it }
        stockage.edit().putString(CLE_LISTE, DecodeursMultiples.json(liste).toString()).apply()
    }

    // ------------------------------------------------------------ the page's side

    private fun garde(l: List<DecodeursMultiples.Canal>) {
        liste = l
        stockage.edit().putString(CLE_LISTE, DecodeursMultiples.json(l).toString()).apply()
        synchronized(this) {
            val ids = l.associateBy { it.id }
            postes.entries.removeIf { (k, p) -> (ids[k]?.let { it.decodeur != p.canal.decodeur || it.cadranHz != p.canal.cadranHz || it.mode != p.canal.mode } ?: true).also { if (it) p.ferme() } }
        }
        l.forEach { allume(it.decodeur) }
    }

    /**
     * The page's gestures (`/api/canaux/…`): option, ajoute (hz = the
     * signal's centre, classe the classifier's, decodeur), retire, ecoute,
     * regle, prereglage (nom, cw and blu: the signals seen), vide.
     */
    fun action(chemin: String, q: Map<String, String>): JSONObject {
        val ok = JSONObject().put("ok", true)
        when (chemin.removePrefix("/api/canaux").trim('/')) {
            "option" -> actif = q["on"] == "1"
            "ajoute" -> {
                val hz = q["hz"]?.toLongOrNull() ?: return erreur("fréquence manquante")
                val classe = q["classe"]?.takeIf { it.isNotBlank() }
                val d = Decodeur.de(q["decodeur"]) ?: DecodeursMultiples.decodeurDe(classe)
                val cadran = q["cadran"]?.toLongOrNull() ?: DecodeursMultiples.cadranPour(d, hz, classe)
                val mode = q["mode"]?.let { m -> runCatching { ModeCanal.valueOf(m) }.getOrNull() } ?: d.mode
                val r = DecodeursMultiples.ajoute(liste, cadran, d, mode)
                r.erreur?.let { return erreur(it) }
                if (!actif) actif = true
                garde(r.liste)
                return ok.put("id", r.id)
            }
            "retire" -> garde(DecodeursMultiples.retire(liste, q["id"]?.toIntOrNull() ?: 0))
            "ecoute" -> garde(DecodeursMultiples.ecoute(liste, q["id"]?.toIntOrNull() ?: 0, q["on"] == "1"))
            "regle" -> {
                val r = DecodeursMultiples.regle(liste, q["id"]?.toIntOrNull() ?: 0, Decodeur.de(q["decodeur"]),
                    q["mode"]?.let { m -> runCatching { ModeCanal.valueOf(m) }.getOrNull() }, q["hz"]?.toLongOrNull())
                r.erreur?.let { return erreur(it) }
                garde(r.liste)
            }
            "prereglage" -> {
                val l = DecodeursMultiples.prereglage(q["nom"].orEmpty(), q["cw"]?.toLongOrNull(), q["blu"]?.toLongOrNull(),
                    q["ecoute"]?.toLongOrNull() ?: 0L) ?: return erreur("préréglage inconnu")
                if (!actif) actif = true
                garde(l)
            }
            "vide" -> garde(emptyList())
            else -> return erreur("geste inconnu")
        }
        return ok
    }

    private fun erreur(m: String) = JSONObject().put("ok", false).put("erreur", m)

    /** The option, the channels with their level and last decode, the presets. */
    fun json(): JSONObject {
        val etats = dsp.etats()
        val l = liste
        val a = JSONArray()
        for (c in l) {
            val e = etats[c.id]
            val p = synchronized(this) { postes[c.id] }
            val o = JSONObject().put("id", c.id).put("hz", c.cadranHz).put("decodeur", c.decodeur.cle).put("nom", c.decodeur.nom)
                .put("mode", c.mode.name).put("ecoute", c.ecoute).put("couleur", c.decodeur.couleur)
                .put("niveau", e?.let { Math.round(it.niveauDb * 10) / 10.0 } ?: JSONObject.NULL)
                .put("crete", e?.let { Math.round(it.crete * 100) / 100.0 } ?: 0.0)
                .put("horsBande", e?.horsBande ?: false).put("loin", e?.parLeLoin ?: false)
                .put("actifRx", e != null)
            when (c.decodeur) {
                Decodeur.SSTV -> if (sstv?.actif != true) o.put("info", "décodage SSTV coupé (onglet SSTV)") else sstv.etat.value.let { s ->
                    o.put("dernier", if (s.mode.isNotEmpty()) s.mode + " · " + s.lignes + "/" + s.hauteur + " lignes" else if (s.derniere.isNotEmpty()) "dernière image : " + s.derniere else "")
                }
                Decodeur.PHONIE -> if (phonie?.actif != true) o.put("info", "Phonie → texte coupé ou non installé (Réglages)") else phonie.derniere()?.takeIf { it.freqHz == c.cadranHz }?.let { b -> o.put("dernier", b.texte.take(120)).put("dernierMs", b.t) }
                else -> if (p != null) o.put("dernier", p.dernier).put("dernierMs", p.dernierMs).put("decodes", p.decodes)
            }
            p?.ft8?.let { f -> o.put("lignes", JSONArray(f.entendus.take(6).map { e2 -> JSONObject().put("t", e2.tranche).put("texte", e2.texte).put("db", e2.rapportDb).put("hz", e2.frequenceHz) })) }
            a.put(o)
        }
        return JSONObject().put("option", actif).put("max", DecodeursMultiples.MAX).put("canaux", a)
            .put("portee", CANAL_PORTEE_HZ)
            .put("prereglages", JSONArray(DecodeursMultiples.PREREGLAGES.map { (k, v) -> JSONObject().put("cle", k).put("nom", v) }))
            .put("decodeurs", JSONArray(Decodeur.entries.map { JSONObject().put("cle", it.cle).put("nom", it.nom).put("couleur", it.couleur).put("mode", it.mode.name) }))
    }

    companion object {
        const val CLE_ON = "pc_canaux"
        const val CLE_LISTE = "pc_canaux_liste"
        /** How far from the PLL a channel is heard (Hz): the page shades what lies beyond. */
        val CANAL_PORTEE_HZ = fr.f4ioz.satcombo.sdr.CanalSecondaire.PORTEE_HZ.toLong()
    }
}
