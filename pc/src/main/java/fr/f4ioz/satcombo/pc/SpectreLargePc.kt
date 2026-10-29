/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.datv.BandeLarge
import fr.f4ioz.satcombo.datv.CreneauxDatv
import fr.f4ioz.satcombo.datv.Datv
import fr.f4ioz.satcombo.datv.NiveauRecu
import fr.f4ioz.satcombo.datv.NomsSignaux
import fr.f4ioz.satcombo.datv.ReglageDatv
import fr.f4ioz.satcombo.datv.SignalLarge
import fr.f4ioz.satcombo.datv.StationsEntendues
import fr.f4ioz.satcombo.datv.VeilleDatv
import fr.f4ioz.satcombo.plateforme.Stockage
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * QO-100's wide band as the ground station sees it (the BATC's public
 * spectrum, read only), in the DATV tab: the signals found in it, their
 * names, a click to receive one, and the « veille » going round them.
 *
 * Off unless chosen; connected only while the option is on and the DATV
 * tab (or the QO-100 one, when it shows the strip too) asked within the
 * last [VU_MS]. Nothing is ever sent there but the
 * opening, the pongs and the close.
 */
class SpectreLargePc(
    private val stockage: Stockage,
    private val datv: DatvPc,
    private val horloge: () -> Long = System::currentTimeMillis,
    /** The spectrum's frames, one connection (by default the ground station's WebSocket). */
    private val ouvre: () -> Source = { Source.de(ClientWebSocketPc.ouvre(HOTE, 443, CHEMIN, PROTOCOLE, AGENT)) },
    /** Starts receiving with these settings; null when started, else why not. */
    private val recois: (ReglageDatv) -> String? = { datv.demarre(it) },
) {
    /** One connection's frames: [recoit] blocks, null once closed. */
    class Source(val recoit: () -> ByteArray?, val ferme: () -> Unit) {
        companion object {
            fun de(c: ClientWebSocketPc) = Source({ c.recoit() }, { c.fermePoliment() })
        }
    }

    /** The callsign read in the picture lately, when there is one. */
    @Volatile var indicatifImage: () -> String? = { null }

    var on: Boolean
        get() = stockage.getBoolean("datv_large", false)
        set(v) { stockage.edit().putBoolean("datv_large", v).apply(); if (!v) courant?.ferme?.invoke() }

    /** Also shown in the QO-100 tab (off unless chosen). */
    var dansQo100: Boolean
        get() = stockage.getBoolean("datv_large_qo100", false)
        set(v) { stockage.edit().putBoolean("datv_large_qo100", v).apply() }

    @Volatile private var vuMs = 0L
    @Volatile private var courant: Source? = null
    @Volatile var etat = ""; private set
    @Volatile private var prochainEssai = 0L
    @Volatile private var delai = DELAI_MIN_MS

    @Volatile var numero = 0L; private set
    @Volatile private var trameMs = 0L
    @Volatile private var derniere = IntArray(0)
    private val recentes = ArrayDeque<IntArray>()
    @Volatile var signaux: List<SignalLarge> = emptyList(); private set

    val noms = NomsSignaux().also { it.reprends(stockage.getString(CLE_NOMS, null), horloge()) }
    /** The stations received lately (any frequency), with when. */
    val entendus = StationsEntendues().also { it.reprends(stockage.getString(CLE_ENTENDUS, null), horloge()) }
    private var entendusGardesMs = 0L
    val veille = VeilleDatv(stockage.getInt("datv_veille_attente_s", 12) * 1000L, stockage.getBoolean("datv_veille_balise", false))
    var veilleEnregistre: Boolean
        get() = stockage.getBoolean("datv_veille_enr", false)
        set(v) { stockage.edit().putBoolean("datv_veille_enr", v).apply() }
    private var enregistrePour: VeilleDatv.Cible? = null

    /** Kept connected without a tab looking at it (the beacons' watch, the automatic scan), when it says so. */
    @Volatile var garde: () -> Boolean = { false }

    /** Connected only while wanted: the option on, the DATV tab open (or [garde]). */
    fun voulu(): Boolean = on && (horloge() - vuMs < VU_MS || runCatching { garde() }.getOrDefault(false))

    /** The reading thread (started once). */
    fun demarre() {
        thread(isDaemon = true, name = "satme-datv-large") {
            while (true) {
                runCatching { boucle() }
                Thread.sleep(500)
            }
        }
    }

    /** One turn: connected and read while wanted, else waiting. */
    fun boucle() {
        if (!voulu()) { etat = if (on) "en attente de l’onglet DATV ou QO-100" else ""; return }
        if (horloge() < prochainEssai) return
        etat = "connexion…"
        val s = runCatching { ouvre() }.getOrElse { echec(it.message ?: it.javaClass.simpleName); return }
        courant = s
        var recues = 0
        try {
            etat = "connecté"
            while (voulu()) {
                val b = s.recoit() ?: break
                if (trame(b)) recues++
            }
        } catch (e: Exception) {
            if (voulu()) etat = "coupé : " + (e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { s.ferme() }
            courant = null
        }
        if (recues > 0) delai = DELAI_MIN_MS
        if (voulu()) echec(if (recues == 0) "aucune trame reçue" else "connexion fermée")
        else etat = if (on) "en attente de l’onglet DATV ou QO-100" else ""
    }

    private fun echec(pourquoi: String) {
        prochainEssai = horloge() + delai
        etat = "indisponible (" + pourquoi + "), nouvel essai dans " + delai / 1000 + " s"
        delai = minOf(delai * 2, DELAI_MAX_MS)
    }

    /** A frame received: kept, its signals found (on the last second's average). */
    fun trame(b: ByteArray): Boolean {
        val v = BandeLarge.valeurs(b)
        if (v.size < 100) return false
        synchronized(recentes) {
            recentes.addLast(v); while (recentes.size > 4) recentes.removeFirst()
            signaux = BandeLarge.detecte(BandeLarge.moyenne(recentes.toList()))
        }
        derniere = v; trameMs = horloge(); numero++
        return true
    }

    /** Fresh frames (the last within 5 s). */
    fun frais(): Boolean = numero > 0 && horloge() - trameMs < 5_000L

    // ------------------------------------------------------------ the receiver

    /** The signal the MiniTiouner is on, when it receives. */
    fun tenu(sg: SignalLarge): Boolean = datv.etat.actif && sur(sg, datv.reglage.cielKhz)

    /**
     * The signal selected on the spectrum: the one the receiver is on,
     * locked or not, with its label as the page shows it. Null without a
     * fresh spectrum or a receiver.
     */
    fun selection(): Pair<SignalLarge, String>? {
        if (!on || !frais() || !datv.etat.actif) return null
        val sg = selectionnee(signaux, datv.reglage.cielKhz) ?: return null
        val nom = noms.nom(sg.centreKhz, sg.srKs, horloge()) ?: if (datv.etat.verrouille) nomRecu() else ""
        return sg to nom
    }

    /** The callsign in the selected signal's label ([selection]), or empty (none selected, a label that is not one). */
    fun indicatifSelection(): String = selection()?.let { fr.f4ioz.satcombo.datv.QrzDatv.indicatifDans(it.second) }.orEmpty()

    /** Receives [khz] at [srKs] (the LNB's oscillator, the input, the supply as they are); null when started. */
    fun accorde(khz: Long, srKs: Int): String? {
        if (khz !in BandeLarge.DEBUT_KHZ..BandeLarge.DEBUT_KHZ + BandeLarge.LARGEUR_KHZ || srKs !in 25..4000) return "signal hors de la bande large"
        return recois(datv.reglage.copy(cielKhz = khz, srKs = srKs))
    }

    /** The operator tuned by hand: the « veille » stops. */
    fun veilleArrete(pourquoi: String = "arrêtée : réglage à la main") { if (veille.active) veille.arrete(pourquoi) }

    /** The name of what is received now: the SDT's, LongMynd's, the station card's, the picture's. */
    fun nomRecu(): String {
        val c = datv.carte.carte?.takeIf { horloge() - datv.carte.carteMs < 60_000L && datv.carte.carteMs >= datv.verrouDepuisMs }?.indicatif
        return datv.sdt.nom.ifBlank { datv.etat.service }.ifBlank { c.orEmpty() }.ifBlank { indicatifImage().orEmpty() }.trim()
    }

    /** Each second: the connection dropped when no longer wanted, the names noted, the « veille » moved on. */
    fun tic() {
        if (!voulu()) courant?.let { runCatching { it.ferme() } }
        val e = datv.etat
        val maintenant = horloge()
        val r = datv.reglage
        if (e.actif && e.verrouille) nomRecu().takeIf { it.isNotEmpty() }?.let { n ->
            val nouveau = entendus.note(n, r.cielKhz, r.debitAccroche(e.srTrouveKs), e.merDb, maintenant)
            if (nouveau || maintenant - entendusGardesMs >= 60_000L) { entendusGardesMs = maintenant; stockage.edit().putString(CLE_ENTENDUS, entendus.texte()).apply() }
        }
        if (e.actif && e.verrouille && r.cielKhz in BandeLarge.DEBUT_KHZ..BandeLarge.DEBUT_KHZ + BandeLarge.LARGEUR_KHZ) {
            // Under the rate tuned (as the spectrum measured it) and the one found, when they differ.
            val debits = listOf(r.srKs, e.srTrouveKs).filter { it > 0 }.map { BandeLarge.debit(it.toDouble()) }.distinct()
            val n = nomRecu()
            var nouveau = false
            if (n.isNotEmpty()) debits.forEach { if (noms.note(r.cielKhz, it, n, maintenant)) nouveau = true }
            if (nouveau) stockage.edit().putString(CLE_NOMS, noms.texte()).apply()
        }
        if (!veille.active) { enregistrePour = null; return }
        if (!frais()) { veillePause = true; return }
        veillePause = false
        val c = veille.tic(maintenant, signaux, e.actif && e.verrouille)
        if (c != null) {
            enregistrePour = null
            accorde(c.khz, c.srKs)?.let { veille.arrete("arrêtée : $it") }
            return
        }
        val cible = veille.cible
        if (veilleEnregistre && cible != null && enregistrePour != cible && e.actif && e.verrouille && e.enregistrement.isEmpty()) {
            enregistrePour = cible
            datv.enregistre(true, noms.nom(cible.khz, cible.srKs, maintenant) ?: nomRecu())
        }
    }
    @Volatile private var veillePause = false

    // ------------------------------------------------------------ the page

    /** The state for the page; [vu]: a tab shows the strip (which keeps the connection). */
    fun json(depuis: Long, vu: Boolean = true): JSONObject {
        if (vu) vuMs = horloge()
        val maintenant = horloge()
        val o = JSONObject().put("ok", true).put("on", on).put("qo100", dansQo100).put("etat", etat).put("n", numero).put("frais", frais())
            .put("debut", BandeLarge.DEBUT_KHZ).put("largeur", BandeLarge.LARGEUR_KHZ).put("balise", Datv.BALISE_KHZ)
            .put("canaux", JSONArray(Datv.CANAUX_KHZ)).put("canauxLarges", JSONArray(BandeLarge.CANAUX_LARGES_KHZ))
            .put("bruit", BandeLarge.BRUIT).put("seuil", BandeLarge.SEUIL).put("unitesDb", BandeLarge.UNITES_PAR_DB)
        if (on && frais() && depuis != numero) o.put("valeurs", JSONArray(derniere))
        if (on && frais()) {
            val tenus = signaux.map { tenu(it) }
            val l = signaux.mapIndexed { i, sg ->
                val nom = noms.nom(sg.centreKhz, sg.srKs, maintenant) ?: if (tenus[i] && datv.etat.verrouille) nomRecu() else ""
                BandeLarge.json(sg).put("nom", nom).put("tenu", tenus[i])
            }
            o.put("signaux", JSONArray(l))
        } else o.put("signaux", JSONArray())
        o.put("recepteur", if (datv.etat.actif) JSONObject().put("ciel", datv.reglage.cielKhz).put("sr", datv.reglage.srKs)
            .put("verrouille", datv.etat.verrouille) else JSONObject.NULL)
        // Our own level on the same scale: the MiniTiouner's state and MER where it listens.
        o.put("monNiveau", NiveauRecu.calcule(datv.etat, datv.reglage, if (on && frais()) signaux else null)?.let { NiveauRecu.json(it) } ?: JSONObject.NULL)
        o.put("entendus", entendus.json())
        o.put("veille", JSONObject().put("on", veille.active).put("etat", if (veille.active && veillePause) "en pause : pas de spectre récent" else veille.etat)
            .put("attente", veille.attenteMs / 1000).put("balise", veille.avecBalise).put("enr", veilleEnregistre))
        return o
    }

    /**
     * The band plan's channels at [srKs] free or busy as the spectrum shows
     * them now (unknown without a fresh one): for the transmission's page.
     */
    fun creneaux(srKs: Int, alpha: Double, notreDescenteKhz: Long?, indicatif: String): List<CreneauxDatv.Creneau> {
        val t = horloge()
        val l = if (on && frais()) signaux else null
        return CreneauxDatv.calcule(srKs, alpha, l, { sg -> noms.nom(sg.centreKhz, sg.srKs, t) ?: "" }, notreDescenteKhz, indicatif)
    }

    fun action(chemin: String, q: Map<String, String>): JSONObject {
        val ok = JSONObject().put("ok", true)
        when (chemin) {
            "/api/datv/large/reglage" -> {
                q["on"]?.let { on = it == "1"; if (on) { vuMs = horloge(); prochainEssai = 0L; delai = DELAI_MIN_MS } else veilleArrete("arrêtée : spectre coupé") }
                q["attente"]?.trim()?.toIntOrNull()?.coerceIn(4, 300)?.let { veille.attenteMs = it * 1000L; stockage.edit().putInt("datv_veille_attente_s", it).apply() }
                q["balise"]?.let { veille.avecBalise = it == "1"; stockage.edit().putBoolean("datv_veille_balise", veille.avecBalise).apply() }
                q["enr"]?.let { veilleEnregistre = it == "1" }
                q["qo100"]?.let { dansQo100 = it == "1" }
            }
            "/api/datv/large/accorde" -> {
                val khz = q["khz"]?.trim()?.toLongOrNull() ?: return JSONObject().put("ok", false).put("erreur", "quelle fréquence ?")
                val sr = q["sr"]?.trim()?.toIntOrNull() ?: return JSONObject().put("ok", false).put("erreur", "quel débit ?")
                veilleArrete()
                accorde(khz, sr)?.let { return JSONObject().put("ok", false).put("erreur", it) }
            }
            "/api/datv/large/veille" -> {
                if (q["on"] == "1") {
                    if (!on) return JSONObject().put("ok", false).put("erreur", "activer d’abord le spectre de la bande large")
                    veille.demarre()
                } else veilleArrete("arrêtée")
            }
            else -> return JSONObject().put("ok", false).put("erreur", "inconnu")
        }
        return ok
    }

    companion object {
        const val HOTE = "eshail.batc.org.uk"
        const val CHEMIN = "/wb/fft"
        const val PROTOCOLE = "fft"
        const val AGENT = "SatMe-PC"
        const val VU_MS = 10_000L
        const val DELAI_MIN_MS = 2_000L
        const val DELAI_MAX_MS = 60_000L
        private const val CLE_NOMS = "datv_large_noms"

        /** True when a receiver tuned to [cielKhz] is on [sg]. */
        fun sur(sg: SignalLarge, cielKhz: Long): Boolean =
            Math.abs(sg.centreKhz - cielKhz) <= maxOf(NomsSignaux.tolerance(sg.srKs), sg.largeurKhz / 2)

        /** The signal of [signaux] a receiver tuned to [cielKhz] is on (the nearest), the beacon aside; null when none. */
        fun selectionnee(signaux: List<SignalLarge>, cielKhz: Long): SignalLarge? =
            signaux.filter { !it.balise && sur(it, cielKhz) }.minByOrNull { Math.abs(it.centreKhz - cielKhz) }
        private const val CLE_ENTENDUS = "datv_entendus"
    }
}
