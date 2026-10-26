/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.cat.PuissanceRf
import fr.f4ioz.satcombo.cat.icomreseau.AnalyseTraceTx
import fr.f4ioz.satcombo.cat.icomreseau.FormatTxReseau
import fr.f4ioz.satcombo.demo.SonDistant
import fr.f4ioz.satcombo.station.EmissionPoste
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * The IC-9700 transmitting from SatMe-PC, here or from the remote page:
 * the PTT held by a button ([EmissionPoste]: one client, its 2 s watchdog,
 * the time-out, the link), the page's microphone to the rig (its USB sound
 * card, or the network link's TX stream), the RF power and its cap.
 *
 * **Nothing transmits by itself**: no PTT at start, none from a stored
 * setting, none after a reconnection. Over the network, PTT passes only
 * while « émission par le réseau autorisée » is ticked — on this PC
 * alone. The microphone reaches the rig only while its own client holds
 * the PTT; the rest of the time its frames are dropped.
 */
class EmissionPostePc(
    private val station: StationPc,
    /** The rig's USB sound card (Java's name), null when not found. */
    private val carteDuPoste: () -> String? = { null },
    private val horloge: () -> Long = System::currentTimeMillis,
    /** Where the microphone goes ([reseau]: the network link's TX stream); replaced by the tests. */
    private val fabriqueSortie: ((reseau: Boolean, carte: String?) -> Sortie?)? = null,
) {
    /** The microphone's way to the rig while keyed. */
    interface Sortie {
        /** [n] samples of [pcm] at [taux]; null when taken, else why not. */
        fun pousse(pcm: ShortArray, n: Int, taux: Int): String?
        fun ferme()
        val nom: String
    }

    private val stockage = station.stockage

    // ------------------------------------------------------------ settings (this PC's, see ImportTelephone)

    /** The time-out, minutes (1 to 10, 3 by default). */
    var totMin: Int
        get() = stockage.getInt("pc_tx_poste_tot_min", 3).coerceIn(1, 10)
        set(v) { stockage.edit().putInt("pc_tx_poste_tot_min", v.coerceIn(1, 10)).apply(); poste.totMs = v.coerceIn(1, 10) * 60_000L }
    /** The highest RF power ever written, percent of the knob (100: none). */
    var plafondPct: Int
        get() = stockage.getInt("pc_tx_poste_plafond", 100).coerceIn(1, 100)
        set(v) { stockage.edit().putInt("pc_tx_poste_plafond", v.coerceIn(1, 100)).apply() }
    /** The rig's USB card for the microphone (Java's name); empty: the one recognised as the rig's. */
    var carte: String
        get() = stockage.getString("pc_tx_poste_carte", "") ?: ""
        set(v) { stockage.edit().putString("pc_tx_poste_carte", v.trim().take(200)).apply() }

    /** LEILA heard lately on QO-100's downlink (set by the station): keyed on QO-100, released at once. */
    @Volatile var leila: () -> Boolean = { false }
    /** QO-100's narrow band signals while SatMe hears it, or null: our level against the beacon's. */
    @Volatile var signauxQo100: () -> List<fr.f4ioz.satcombo.domain.SignalQo100>? = { null }
    /** Our level against the beacon's while keyed on QO-100 (dB), when both are seen. */
    @Volatile var niveauQo100 = fr.f4ioz.satcombo.sstv.NiveauSstvQo100.Mesure(null, null); private set

    /** Another transmitter SatMe drives that excludes the rig (the Pluto's narrow band), or null. */
    @Volatile var autreEmission: () -> String? = { null }

    /** On QO-100 (« Appliquer au poste »): why the rig may not be keyed where the cockpit is, or null. */
    private fun refusQo100(): String? {
        autreEmission()?.let { return it }
        val q = station.qo100.etat.value
        if (!q.auPoste || q.transpondeur != fr.f4ioz.satcombo.domain.Qo100.NB.cle) return null
        if (!q.emissionPermise) return "QO-100 : l'accord est sur une balise ou hors du plan de bande, écoute seule"
        if (q.monteeRetenue) return "QO-100 : fréquence d'urgence proposée par SatMe, montée non écrite au poste"
        if (leila()) return "LEILA entendue à l'instant : attendre et baisser la puissance"
        return null
    }

    /** The CAT went through the network server (as connected, whatever the setting says since). */
    private val reseau: Boolean get() = station.catParReseau

    // ------------------------------------------------------------ the PTT

    val poste = EmissionPoste(object : EmissionPoste.Lien {
        override fun pret(): String? {
            val e = station.etat.value
            if (!e.catConnecte) return "poste non connecté (Réglages › Poste)"
            if (e.catModele != "IC9700") return "émission par SatMe : IC-9700 seulement"
            if (perteLien) return "le poste ne répond plus"
            if (reseau && !e.catSimule && !station.txReseauAutorise)
                return "émission par le réseau non autorisée (Réglages › Émission, sur le PC de la station)"
            return refusQo100()
        }
        override fun joignable(): Boolean {
            val e = station.etat.value
            if (!e.catConnecte || perteLien) return false
            return !reseau || e.catSimule || station.icomReseau.session?.connecte == true
        }
        override suspend fun ptt(on: Boolean): Boolean = station.pttPoste(on)
    }, horloge).also { it.totMs = totMin * 60_000L }

    /** The rig silent while keyed (its TX state unread for 3 s): the link counts as lost. */
    @Volatile private var perteLien = false
    @Volatile private var reponduMs = 0L
    @Volatile private var lectureTxMs = 0L
    /** The rig's own TX state as read while SatMe keys it (null: unread). */
    @Volatile var posteEmet: Boolean? = null; private set

    @Volatile private var sortie: Sortie? = null
    @Volatile private var sortieErreur = ""
    private val echantillons = AtomicLong()
    private val trames = AtomicLong()
    private val ignorees = AtomicLong()
    @Volatile private var crete = 0.0
    @Volatile private var creteMs = 0L
    @Volatile private var microClient = ""
    @Volatile private var microDepuis = 0L

    /** The CAT disconnecting: the PTT released first, while the rig can still hear it. */
    init {
        station.avantCoupureCat = { if (poste.etat.emet || poste.offEnAttente) poste.relache("poste déconnecté") }
    }

    /**
     * Keys for [client]; null once keyed, else why not. Under a cap, the
     * level read must be under it first (written and read back by the CAT
     * loop, 1.5 s at most) — no keying at an unknown power.
     */
    fun appuie(client: String, distant: Boolean, adresse: String): String? {
        if (!poste.etat.emet) {
            poste.totMs = totMin * 60_000L
            perteLien = false
            plafondAvant()?.let { return it }
        }
        val r = runBlocking { poste.appuie(client, distant, adresse) }
        if (r == null) { reponduMs = horloge(); lectureTxMs = 0L; posteEmet = null; ouvreSortie() }
        return r
    }

    private fun plafondAvant(): String? {
        val cap = plafondPct
        if (cap >= 100) return null
        val max = PuissanceRf.niveau(cap)
        val lu = station.puissanceLue
        if (lu != null && lu <= max && horloge() - station.puissanceLueMs < 30_000L) return null
        station.puissanceAEcrire = minOf(lu ?: max, max)
        val fin = horloge() + 1_500L
        while (horloge() < fin) {
            Thread.sleep(50)
            val v = station.puissanceLue
            if (v != null && v <= max && station.puissanceAEcrire == null) return null
        }
        return "puissance non confirmée sous le plafond (" + cap + " %) : PTT refusé"
    }

    fun vie(client: String): Boolean = poste.vie(client)

    fun relache(raison: String) = runBlocking { poste.relache(raison) }

    /** Everything released now, waited for at most 1.5 s (SatMe-PC closing). */
    fun arreteTout(raison: String) {
        runBlocking { withTimeoutOrNull(1_500L) { poste.relache(raison) } }
        fermeSortie()
    }

    /** A few times a second: the guards, the rig's TX state read back, the microphone's way closed after an over. */
    fun tic() {
        val t = horloge()
        // LEILA while SatMe keys the rig on QO-100: released before anything else.
        if (poste.etat.emet && station.qo100.etat.value.auPoste && leila()) { relache("LEILA détectée : émission coupée"); return }
        if (poste.etat.emet && station.qo100.etat.value.auPoste) {
            val q = station.qo100.etat.value
            niveauQo100 = signauxQo100()?.let { fr.f4ioz.satcombo.sstv.NiveauSstvQo100.mesure(it, q.monteeHz + 300, q.monteeHz + 2_800) }
                ?: fr.f4ioz.satcombo.sstv.NiveauSstvQo100.Mesure(null, null)
        } else niveauQo100 = fr.f4ioz.satcombo.sstv.NiveauSstvQo100.Mesure(null, null)
        if (poste.etat.emet && t - lectureTxMs >= 500L) {
            lectureTxMs = t
            val lu = runBlocking { withTimeoutOrNull(1_000L) { runCatching { station.etatTxPoste() }.getOrNull() } }
            if (lu != null) { reponduMs = t; posteEmet = lu } else if (t - reponduMs > 3_000L) perteLien = true
        }
        runBlocking { poste.tic() }
        if (!poste.etat.emet) {
            perteLien = false
            if (sortie != null) fermeSortie()
        }
    }

    // ------------------------------------------------------------ the microphone

    private fun ouvreSortie() {
        if (sortie != null) return
        val e = station.etat.value
        val nomCarte = carte.ifBlank { runCatching { carteDuPoste() }.getOrNull().orEmpty() }.ifBlank { null }
        sortie = runCatching {
            fabriqueSortie?.invoke(reseau && !e.catSimule, nomCarte) ?: when {
                // The simulated rig: the samples counted, no card ever opened.
                e.catSimule -> SortieComptee("poste simulé")
                reseau -> SortieReseau(station)
                nomCarte != null -> SortieCarte(nomCarte)
                else -> null
            }
        }.getOrNull()
        sortieErreur = when {
            sortie != null -> ""
            reseau -> "son par le réseau indisponible"
            else -> "carte son du poste introuvable : le micro ne part pas (Réglages › Émission)"
        }
        if (reseau && !e.catSimule && !station.icomReseau.sonTxDemande)
            sortieErreur = "son d'émission non demandé à la connexion : reconnecter le poste après avoir autorisé l'émission par le réseau"
    }

    private fun fermeSortie() {
        val s = sortie ?: return
        sortie = null
        runCatching { s.ferme() }
    }

    /** A frame of microphone from [client]: to the rig while it holds the PTT, else dropped. */
    fun micro(client: String, l: SonDistant.Lue) {
        trames.incrementAndGet()
        var c = 0.0
        for (v in l.pcm) { val a = kotlin.math.abs(v.toInt()) / 32768.0; if (a > c) c = a }
        val t = horloge()
        if (t - creteMs > 300L || c > crete) { crete = c; creteMs = t }
        microClient = client
        if (!poste.tenuPar(client)) { ignorees.incrementAndGet(); return }
        poste.vie(client)
        val s = sortie ?: return
        s.pousse(l.pcm, l.pcm.size, l.rate)?.let { sortieErreur = it } ?: run { echantillons.addAndGet(l.pcm.size.toLong()) }
    }

    /**
     * Serves the microphone's WebSocket (the opening already answered): its
     * frames to [micro]; the socket closed while its client holds the PTT
     * releases it at once.
     */
    fun sertMicro(c: Socket, client: String) {
        runCatching { c.tcpNoDelay = true; c.soTimeout = 0 }
        val e = c.getInputStream()
        val s = c.getOutputStream()
        val verrou = Any()
        fun ecrit(b: ByteArray) = synchronized(verrou) { runCatching { s.write(b); s.flush() }.isSuccess }
        if (!ecrit(WebSocketPc.texte(JSONObject().put("t", "bonjour").put("micro", true).put("taux", 48_000).toString()))) return
        microDepuis = horloge()
        try {
            while (true) {
                val t = WebSocketPc.litTrame(e) ?: break
                when (t.opcode) {
                    WebSocketPc.BINAIRE -> SonDistant.litAmont(t.donnees)?.let { micro(client, it) }
                    WebSocketPc.TEXTE -> runCatching { JSONObject(String(t.donnees, Charsets.UTF_8)) }.getOrNull()?.let { m ->
                        if (m.optString("t") == "ping") ecrit(WebSocketPc.texte(JSONObject().put("t", "pong").put("c", m.optDouble("c"))
                            .put("emet", poste.tenuPar(client)).toString()))
                    }
                    WebSocketPc.PING -> ecrit(WebSocketPc.trame(WebSocketPc.PONG, t.donnees))
                    WebSocketPc.FERMETURE -> { ecrit(WebSocketPc.trame(WebSocketPc.FERMETURE, t.donnees.copyOf(minOf(2, t.donnees.size)))); break }
                }
            }
        } finally {
            if (poste.tenuPar(client)) relache("micro coupé")
        }
    }

    // ------------------------------------------------------------ RF power

    /** A new power, percent of the knob, held under the cap; the level asked (0..255). */
    fun reglePuissance(pct: Int): Int {
        val n = PuissanceRf.plafonne(PuissanceRf.niveau(pct), plafondPct)
        station.puissanceAEcrire = n
        return n
    }

    // ------------------------------------------------------------ the page

    /** The rig's TX frequency now (its uplink when the loop drives it, else the dial read), for the watts. */
    private fun frequenceTx(): Long? {
        val ce = station.stationCat.etat.value
        return ce.monteeHz?.let { station.convTx().versPoste(it) } ?: ce.descenteHz
    }

    fun json(client: String?): JSONObject {
        val e = poste.etat
        val t = horloge()
        val ee = station.etat.value
        val lu = station.puissanceLue
        val f = frequenceTx()
        val o = JSONObject().put("ok", true)
            .put("poste", station.posteIc9700()).put("modele", ee.catModele).put("connecte", ee.catConnecte).put("simule", ee.catSimule)
            .put("liaison", if (reseau) "reseau" else "usb")
            .put("pret", posteLienPret() == null).put("pourquoi", posteLienPret() ?: "")
            .put("emet", e.emet).put("raison", e.raison).put("erreur", e.erreur).put("offEnAttente", poste.offEnAttente)
            .put("restant", poste.restantS(t) ?: JSONObject.NULL).put("tot", totMin)
            .put("posteEmet", posteEmet ?: JSONObject.NULL)
            .put("plafond", plafondPct).put("presets", JSONArray(PuissanceRf.PRESETS))
            .put("puissance", lu ?: JSONObject.NULL).put("pct", lu?.let { PuissanceRf.pourcent(it) } ?: JSONObject.NULL)
            .put("watts", if (lu != null && f != null) PuissanceRf.watts("IC9700", lu, f)?.let { Math.round(it * 10) / 10.0 } ?: JSONObject.NULL else JSONObject.NULL)
            .put("maxWatts", f?.let { PuissanceRf.maxWatts("IC9700", it) } ?: JSONObject.NULL)
            .put("hzTx", f ?: JSONObject.NULL)
            .put("sortie", sortie?.nom ?: "").put("sortieErreur", sortieErreur)
            .put("microDb", if (t - creteMs < 1_000L && crete > 0) Math.round(20 * Math.log10(crete) * 10) / 10.0 else JSONObject.NULL)
            .put("echantillons", echantillons.get()).put("trames", trames.get()).put("ignorees", ignorees.get())
            .put("reseauAutorise", station.txReseauAutorise).put("sonTxDemande", station.icomReseau.sonTxDemande)
            .put("carte", carte).put("carteDuPoste", runCatching { carteDuPoste() }.getOrNull() ?: "")
            .put("chienS", poste.chienMs / 1000.0)
            .put("txMarque", station.settings.icomTxMarque).put("txOctets", station.settings.icomTxOctets)
            .put("txMarqueDefaut", FormatTxReseau.MARQUE_DEFAUT)
            .put("qo100", JSONObject().put("ecart", niveauQo100.ecartDb ?: JSONObject.NULL).put("tropFort", (niveauQo100.ecartDb ?: -99.0) > 0.0)
                .put("refus", refusQo100() ?: ""))
        if (e.emet) o.put("client", if (e.distant) "un autre appareil" + (if (e.adresse.isNotBlank()) " (" + e.adresse + ")" else "") else "la page de ce PC")
            .put("moi", client != null && client == e.client).put("depuisS", (t - e.depuisMs) / 1000)
        return o
    }

    private fun posteLienPret(): String? {
        val ee = station.etat.value
        if (!ee.catConnecte) return "poste non connecté (Réglages › Poste)"
        if (ee.catModele != "IC9700") return "émission par SatMe : IC-9700 seulement"
        if (reseau && !ee.catSimule && !station.txReseauAutorise) return "émission par le réseau non autorisée (Réglages › Émission, sur le PC de la station)"
        return refusQo100()
    }

    /**
     * The page's settings and power. [local]: asked from this PC — only it
     * may allow transmitting over the network.
     */
    fun reglage(q: Map<String, String>, local: Boolean): JSONObject {
        // The guards (network allowed, time-out, cap) and the rig's card: from this PC only; the power, from anyone.
        if (!local && listOf("reseau", "tot", "plafond", "carte").any { it in q })
            return JSONObject().put("ok", false).put("erreur", "ce réglage se change seulement sur le PC de la station")
        q["reseau"]?.let { v ->
            station.txReseauAutorise = v == "1"
            // Withdrawn: whatever is keyed is released at once.
            if (v != "1" && reseau) relache("émission par le réseau retirée")
        }
        q["tot"]?.toIntOrNull()?.let { totMin = it }
        q["plafond"]?.toIntOrNull()?.let { p ->
            plafondPct = p
            val max = PuissanceRf.niveau(plafondPct)
            station.puissanceLue?.takeIf { it > max }?.let { station.puissanceAEcrire = max }
        }
        q["carte"]?.let { carte = it }
        q["pct"]?.toIntOrNull()?.let { reglePuissance(it) }
        q["lire"]?.let { station.puissanceARelire = true }
        return JSONObject().put("ok", true)
    }

    /** A capture of the radio's own software transmitting: analysed, its mark and size learnt when clear. */
    fun apprendsTrace(trace: ByteArray): JSONObject {
        val s = station.settings
        try {
            val b = AnalyseTraceTx.analyse(trace, s.icomPortControle, s.icomPortAudio)
            val o = JSONObject().put("ok", b.ok).put("message", b.message).put("paquets", b.paquets)
            if (!b.ok) return o.put("erreur", b.message)
            val taux = fr.f4ioz.satcombo.cat.icomreseau.CodecReseau.de(s.icomCodec).taux
            o.put("marques", JSONObject(b.marques.mapKeys { "%04x".format(it.key) })).put("tailles", JSONObject(b.tailles.mapKeys { it.key.toString() }))
                .put("longueurCoherente", b.longueurCoherente).put("compteSuit", b.compteSuit)
                .put("format", b.format?.name?.lowercase() ?: "").put("conforme", b.conforme(taux))
            b.demande?.let { d -> o.put("demande", JSONObject().put("actif", d.actif).put("codec", d.codec).put("taux", d.taux).put("tamponMs", d.tamponMs)) }
            val marque = b.marque; val octets = b.octets
            if (marque != null && octets != null && b.longueurCoherente) {
                s.icomTxMarque = marque; s.icomTxOctets = octets
                o.put("appris", true).put("marque", "%04x".format(marque)).put("octets", octets)
            }
            return o
        } catch (e: Exception) {
            return JSONObject().put("ok", false).put("erreur", "trace illisible")
        } finally { trace.fill(0) }
    }

    /** Starts the guards' loop (5 times a second). */
    fun demarre() {
        thread(isDaemon = true, name = "satme-tx-poste") {
            while (true) {
                runCatching { tic() }
                Thread.sleep(200)
            }
        }
    }

    // ------------------------------------------------------------ the ways to the rig

    /** Nothing opened: the samples counted (the simulated rig). */
    class SortieComptee(override val nom: String) : Sortie {
        val recus = AtomicLong()
        override fun pousse(pcm: ShortArray, n: Int, taux: Int): String? { recus.addAndGet(n.toLong()); return null }
        override fun ferme() {}
    }

    /** The rig's USB sound card, 48 kHz, a 60 ms cushion. */
    class SortieCarte(val mixer: String) : Sortie {
        override val nom = mixer
        private val hp = HautParleurPc(TAUX, 0.06) { HautParleurPc.ligne(TAUX, mixer) }
        private var reech: fr.f4ioz.satcombo.phonie.Reechantillonneur? = null
        override fun pousse(pcm: ShortArray, n: Int, taux: Int): String? {
            if (taux == TAUX) { hp.pousse(pcm, n); return null }
            val r = reech?.takeIf { it.entree == taux } ?: fr.f4ioz.satcombo.phonie.Reechantillonneur(taux, TAUX).also { reech = it }
            val y = r.pousse(pcm, n)
            hp.pousse(ShortArray(y.size) { (y[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }, y.size)
            return null
        }
        override fun ferme() = hp.ferme()
        companion object { const val TAUX = 48_000 }
    }

    /** The network link's TX stream. */
    class SortieReseau(private val station: StationPc) : Sortie {
        override val nom = "réseau (" + station.settings.icomHote + ")"
        override fun pousse(pcm: ShortArray, n: Int, taux: Int): String? = station.icomReseau.sonTx(pcm, n, taux)
        override fun ferme() = station.icomReseau.finSonTx()
    }
}
