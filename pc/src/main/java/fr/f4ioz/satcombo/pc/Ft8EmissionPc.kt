/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.Ft8Montee
import fr.f4ioz.satcombo.domain.Ft8Onde
import fr.f4ioz.satcombo.domain.Ft8Pliage
import fr.f4ioz.satcombo.domain.Ft8Qso
import fr.f4ioz.satcombo.domain.Ft8Signal
import fr.f4ioz.satcombo.domain.Ft8Veille
import fr.f4ioz.satcombo.plateforme.Stockage
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * FT8 and FT4 on the air from SatMe-PC: the cockpit's FT8/FT4 tab sends
 * through the rig SatMe already keys ([EmissionPostePc]: its PTT, its
 * guards, its way to the rig's USB sound card or the network link), in the
 * slots of the decoder that listens ([Ft8Pc]).
 *
 * **Attended, never robotic** (the condition attached to the names FT8 and
 * FT4): a contact starts only from the operator — a decoded line answered,
 * or « Appeler CQ »; within it the sequence may advance by itself; and the
 * attendance watchdog ([Ft8Qso.chienExpire]) stops everything after a few
 * minutes without an operator action, CQ included. The page must also say
 * it is there ([vie]): closed or unreachable, the transmitter stops.
 *
 * Every rule of the rig's TX applies, being its PTT: the demonstration
 * ([ModeDemo]) never keys anything — it simulates, the audio made and
 * counted, fake stations answering ([Ft8DemoPc]); QO-100's beacons and
 * LEILA, the network link's authorisation, the TX time-out, the power cap,
 * the other transmitters are [EmissionPostePc]'s. Here on top: the PC's
 * clock read from the stations (refused beyond ±0.5 s in FT4, ±1 s in FT8),
 * the decoder listening, a satellite with an uplink and the signal inside
 * its passband.
 */
class Ft8EmissionPc(
    private val stockage: Stockage,
    private val ft8: Ft8Pc,
    /** The rig's TX; null: nothing can be keyed (the tests of the sequence alone). */
    private val poste: EmissionPostePc?,
    private val moi: () -> String,
    private val carre: () -> String,
    /** Where the rig is: a satellite and its transponder, or terrestrial. */
    private val liaison: () -> Liaison = { Liaison() },
    private val horloge: () -> Long = System::currentTimeMillis,
    /** True: simulated (the demonstration), never a key. */
    private val simule: () -> Boolean = { ModeDemo.actif },
    /** Notes a finished contact in the log; null when noted, else why not. */
    private val noteur: (Ft8Qso.Fin, String, String) -> String? = { _, _, _ -> "carnet indisponible" },
    /** The rig's data mode (USB-D); null: not switched (the tests of the sequence alone). */
    private val modeDonnees: ModeDonnees? = null,
    val cadence: Int = 48_000,
) {
    /**
     * The rig's data mode as the station switches it ([StationPc.basculeDonnees]):
     * whether it can, whether it is on, the mode it shows.
     */
    interface ModeDonnees {
        /** The connected rig's data mode is switched by SatMe (an IC-9700). */
        fun possible(): Boolean
        /** Keying may go ahead: the data mode is on when wanted. */
        fun pret(): Boolean
        /** The rig's mode as read (« USB-D »), null when unknown. */
        fun modePoste(): String?
    }

    /** The rig's situation for an FT8/FT4 transmission. */
    data class Liaison(
        /** The satellite worked (null: terrestrial, the rig's own frequency). */
        val satellite: String? = null,
        /** The chosen transmitter has no uplink (a beacon, a downlink only): nothing to send to. */
        val sansMontee: Boolean = false,
        /** The rig's uplink and downlink dials (sky Hz) as the Doppler loop sets them. */
        val monteeHz: Long? = null,
        val descenteHz: Long? = null,
        /** An inverting transponder (LSB up, USB down). */
        val inverse: Boolean = false,
        val monteeBasHz: Long? = null,
        val monteeHautHz: Long? = null,
        val rangeRateKmS: Double = 0.0,
        /** A linear transponder (SSB through it); false: an FM satellite, where FT8/FT4 is not sent. */
        val lineaire: Boolean = true,
    )

    val qso = Ft8Qso()

    // ------------------------------------------------------------ settings (this PC's)

    /** Our TX audio frequency, lowest tone (Hz). */
    var frequenceHz: Int
        get() = stockage.getInt("pc_ft8_tx_hz", 1500).coerceIn(BAS_HZ, HAUT_HZ)
        set(v) { stockage.edit().putInt("pc_ft8_tx_hz", v.coerceIn(BAS_HZ, HAUT_HZ)).apply() }
    /** « Garder la fréquence d'émission »: never moved by SatMe, even onto a clear spot. */
    var tient: Boolean
        get() = stockage.getBoolean("pc_ft8_tx_tient", false)
        set(v) { stockage.edit().putBoolean("pc_ft8_tx_tient", v).apply() }
    var auto: Boolean
        get() = stockage.getBoolean("pc_ft8_tx_auto", true)
        set(v) { stockage.edit().putBoolean("pc_ft8_tx_auto", v).apply() }
    /** The audio level sent (dBFS of the peak), −30 to 0. */
    var niveauDb: Int
        get() = stockage.getInt("pc_ft8_tx_niveau", -12).coerceIn(-30, 0)
        set(v) { stockage.edit().putInt("pc_ft8_tx_niveau", v.coerceIn(-30, 0)).apply() }
    var chienMin: Int
        get() = stockage.getInt("pc_ft8_tx_chien", 3).coerceIn(1, 10)
        set(v) { stockage.edit().putInt("pc_ft8_tx_chien", v.coerceIn(1, 10)).apply(); qso.chienMin = v }
    /** CQ in the even slots (« 1er » in WSJT-X) or the odd ones. */
    var pairCq: Boolean
        get() = stockage.getBoolean("pc_ft8_tx_pair", true)
        set(v) { stockage.edit().putBoolean("pc_ft8_tx_pair", v).apply() }
    var rrr: Boolean
        get() = stockage.getBoolean("pc_ft8_tx_rrr", false)
        set(v) { stockage.edit().putBoolean("pc_ft8_tx_rrr", v).apply(); qso.rrr = v }

    /**
     * « Mode données (USB-D) »: while transmitting is armed, the rig goes to
     * USB-D / LSB-D (both bands in satellite mode) and takes its audio from
     * *DATA MOD*; its flags are put back after. On by default.
     */
    var donnees: Boolean
        get() = stockage.getBoolean("pc_ft8_tx_donnees", true)
        set(v) { stockage.edit().putBoolean("pc_ft8_tx_donnees", v).apply() }

    init { qso.chienMin = chienMin; qso.rrr = rrr }

    // ------------------------------------------------------------ state

    /** Enable TX: the sequence may send in its slots. */
    @Volatile var arme = false; private set
    @Volatile var raison = ""; private set
    @Volatile var erreur = ""; private set
    /** The mode the transmitter was armed in: changed under it, it stops. */
    private var modeArme = "FT8"
    private var derniereTranche = Long.MIN_VALUE
    @Volatile private var vieMs = 0L
    @Volatile var envois = 0; private set
    /** The demonstration's fake stations, told what we « sent ». */
    @Volatile var auEnvoi: ((texte: String, debutTranche: Long, hz: Int) -> Unit)? = null

    /** One transmission under way. */
    private class Emission(val texte: String, val tx: Int, val tranche: Long, val debutTrancheMs: Long, val pcm: ShortArray,
                           val debutSonMs: Long, val simule: Boolean, val hz: Int) {
        var pousses = 0
        var finSonMs = 0L
    }
    @Volatile private var enCours: Emission? = null
    /** The last transmission: what, when, at what frequency. */
    @Volatile var dernier: Triple<String, Long, Int>? = null; private set

    val emet: Boolean get() = enCours?.let { !it.simule } == true

    /**
     * The rig should be in its data modes now: « Mode données » ticked,
     * listening, transmitting armed or an over under way; never in the
     * demonstration. False again when disarmed, listening stopped, or the
     * page gone (that disarms): the flags are put back then.
     */
    val veutDonnees: Boolean get() = donnees && !simule() && ft8.enMarche && (arme || enCours != null)
    val emetSimule: Boolean get() = enCours?.simule == true

    private fun ft4() = ft8.mode == "FT4"
    private fun duree() = Ft8Qso.dureeTrancheMs(ft4())

    /** The page is there (each second while armed). */
    fun vie() { vieMs = horloge() }

    // ------------------------------------------------------------ the operator

    private fun prepare(t: Long) {
        qso.moi = moi(); qso.carre = carre(); qso.ft4 = ft4(); qso.chienMin = chienMin; qso.rrr = rrr
        vieMs = t
    }

    /** A clear frequency for a new contact, unless held; never a decoded station's own. */
    private fun choisitFrequence() {
        if (tient) return
        val t = horloge()
        // Our own signal heard back (a satellite) is not a station to avoid.
        val m = Ft8Qso.base(moi())
        val occupees = ft8.entendus.filter { it.mode == ft8.mode && t - it.tranche < 3 * duree() && it.appelant?.let { a -> Ft8Qso.base(a) } != m }
            .map { it.frequenceHz }
        Ft8Qso.frequenceLibre(occupees, frequenceHz, ft4(), BAS_HZ, HAUT_HZ)?.let { if (it != frequenceHz) frequenceHz = it }
    }

    /** Answer a decoded line (the operator's double-click); null when armed, else why not. */
    @Synchronized fun repond(r: Ft8Qso.Recu): String? {
        val t = horloge()
        prepare(t)
        if (qso.moi.isBlank()) return "indicatif de la station manquant (Réglages › Station)"
        qso.repond(r, t)?.let { return it }
        choisitFrequence()
        return arme(t)
    }

    /** « Appeler CQ »; null when armed, else why not. */
    @Synchronized fun appelleCq(): String? {
        val t = horloge()
        prepare(t)
        if (qso.moi.isBlank()) return "indicatif de la station manquant (Réglages › Station)"
        qso.appelleCq(pairCq, t)
        choisitFrequence()
        return arme(t)
    }

    /** Enable TX by hand (the sequence where it is). */
    @Synchronized fun active(on: Boolean): String? {
        val t = horloge()
        if (!on) { arrete("émission désactivée"); return null }
        prepare(t)
        qso.geste(t)
        if (qso.aEnvoyer() == null) return "rien à envoyer : double-cliquer une ligne ou « Appeler CQ »"
        return arme(t)
    }

    private fun arme(t: Long): String? {
        refusPermanent()?.let { arrete(it, true); return it }
        arme = true; modeArme = ft8.mode; raison = ""; erreur = ""
        ft8.presse = true
        qso.geste(t)
        return null
    }

    /** Halt: TX off now (an over under way cut, the PTT released first), from anyone. */
    @Synchronized fun arrete(pourquoi: String = "arrêt demandé", estErreur: Boolean = false) {
        val e = enCours
        enCours = null
        if (e != null && !e.simule) runCatching { poste?.relache("FT8/FT4 : $pourquoi") }
        if (arme || e != null || estErreur) { raison = pourquoi; if (estErreur) erreur = pourquoi }
        arme = false
        ft8.presse = false
        // The sequence stays where it is: « Activer » sends from there again, on the operator's word.
    }

    /** An operator gesture that changes nothing else (a message chosen, a setting): the watchdog starts again. */
    @Synchronized fun geste() { qso.geste(horloge()) }

    @Synchronized fun choisit(n: Int) { qso.choisit(n, horloge()) }
    @Synchronized fun edite(n: Int, texte: String): String? {
        if (texte.isNotBlank() && Ft8Pliage.plie(texte) == null) return "message impossible à coder en FT8/FT4 : « " + texte.trim() + " »"
        qso.edite(n, texte, horloge()); return null
    }

    /** The finished contact to the log (confirmed by the operator); null when noted. */
    @Synchronized fun note(indicatif: String?, carre: String?, rs: String?, rr: String?, note: String): String? {
        val f = qso.aNoter ?: return "aucun QSO à noter"
        val g = f.copy(indicatif = indicatif?.trim()?.uppercase()?.ifBlank { null } ?: f.indicatif,
            carre = carre?.trim()?.uppercase() ?: f.carre, rapportEnvoye = rs?.trim() ?: f.rapportEnvoye, rapportRecu = rr?.trim() ?: f.rapportRecu)
        noteur(g, ft8.mode, note)?.let { return it }
        qso.oublieFin()
        return null
    }

    @Synchronized fun oublieFin() { qso.oublieFin() }

    // ------------------------------------------------------------ the guards

    /** Our clock against the stations', read from the decodes' DT (null: not enough of them). */
    fun decalageHorloge(t: Long = horloge()): Double? =
        Ft8Veille.horloge(ft8.entendus.filter { it.mode == ft8.mode }.map { it.tranche to it.decalageS }, t)?.decalageS

    /** Why transmitting is refused whatever the slot (no key asked), or null. */
    fun refusPermanent(t: Long = horloge()): String? {
        if (moi().isBlank()) return "indicatif de la station manquant (Réglages › Station)"
        if (!ft8.enMarche) return "écoute FT8/FT4 arrêtée : l'émission suit le décodage, démarrer l'écoute d'abord"
        Ft8Qso.refusHorloge(decalageHorloge(t), ft4())?.let { return it }
        val l = runCatching { liaison() }.getOrDefault(Liaison())
        if (l.satellite != null && !l.sansMontee && !l.lineaire) return "« " + l.satellite + " » : satellite FM, FT8/FT4 seulement par un transpondeur linéaire"
        if (l.satellite != null && l.sansMontee) return "« " + l.satellite + " » : l'émetteur choisi n'a pas de montée (balise ou descente seule), rien à émettre"
        val m = l.monteeHz; val b = l.monteeBasHz; val h = l.monteeHautHz
        if (l.satellite != null && m != null && b != null && h != null)
            Ft8Montee.horsBande(m, frequenceHz, Ft8Qso.largeurHz(ft4()), l.inverse, l.rangeRateKmS, b, h)?.let { return it }
        if (!simule()) {
            val p = poste ?: return "pas de poste pour émettre"
            p.refus()?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------ the loop

    /** Often (every 20 ms): decodes read, guards, the slot's start, the audio paced to the rig. */
    @Synchronized fun tic(t: Long = horloge()) {
        qso.moi = moi(); qso.carre = carre(); qso.ft4 = ft4()
        // What the last slot brought, the sequence moved (only once something was started).
        val lignes = ft8.entendus.asSequence().filter { it.mode == ft8.mode && t - it.tranche < 4 * duree() }
            .map { Ft8Qso.Recu(it.texte, it.appelant, it.appele, it.locator, it.rapportDb, it.frequenceHz, it.tranche,
                Ft8Qso.estPaire(Ft8Qso.tranche(it.tranche, it.mode == "FT4"))) }.toList()
        if (lignes.isNotEmpty()) qso.recoit(lignes, auto)
        ft8.qsoEnCours = qso.partenaire?.takeIf { qso.etape != Ft8Qso.Etape.REPOS && qso.etape != Ft8Qso.Etape.FINI && qso.moi.isNotBlank() }?.let {
            fr.f4ioz.satcombo.domain.Ft8Decodeur.Apriori(qso.moi, it, qso.rapportRecu != null || qso.etape.ordinal >= Ft8Qso.Etape.R_RAPPORT.ordinal, qso.sonCarre)
        }

        val e = enCours
        if (e != null) { poursuit(e, t); return }
        if (!arme) return
        if (ft8.mode != modeArme) { arrete("mode changé (" + modeArme + " → " + ft8.mode + ")"); return }
        if (qso.chienExpire(t)) { arrete("chien de garde : aucune action de l'opérateur depuis " + chienMin + " min (règle FT8/FT4 : pas de QSO sans opérateur)"); return }
        if (t - vieMs > VIE_MS) { arrete("page SatMe fermée ou injoignable : émission coupée"); return }
        val texte = qso.aEnvoyer()
        if (texte == null) { arrete(if (qso.etape == Ft8Qso.Etape.FINI) "QSO terminé" else "plus rien à envoyer"); return }
        val n = Ft8Qso.tranche(t, ft4())
        if (n == derniereTranche || Ft8Qso.estPaire(n) != qso.pair) return
        val debutTranche = n * duree()
        val dans = t - debutTranche
        if (dans < Ft8Qso.DEBUT_MS - AVANCE_PTT_MS) return
        val max = Ft8Qso.debutMaxMs(ft4())
        if (dans > max) { derniereTranche = n; return }
        // The previous slot's decode first (what it holds may change the message), a little while at most.
        val attente = max - (if (ft4()) 500L else 1_000L)
        if (ft8.derniereTrancheDecodee < debutTranche - duree() && dans < attente) return
        // The rig not yet in USB-D (switched between two Doppler rounds, a few tenths of a second): wait, or skip this slot.
        if (veutDonnees && modeDonnees?.pret() == false) return
        derniereTranche = n
        demarre(n, debutTranche, texte, t)
    }

    private fun demarre(n: Long, debutTranche: Long, texte: String, t: Long) {
        refusPermanent(t)?.let { arrete(it, true); return }
        val tons = Ft8Onde.tons(texte, ft4()) ?: run { arrete("message impossible à coder : $texte", true); return }
        val mode = if (ft4()) Ft8Signal.FT4 else Ft8Signal.FT8
        val amplitude = Math.pow(10.0, niveauDb / 20.0)
        val pcm = Ft8Onde.pcm(Ft8Onde.onde(tons, mode, frequenceHz.toDouble(), cadence.toDouble(), amplitude))
        val sim = simule()
        if (!sim) {
            val p = poste ?: run { arrete("pas de poste pour émettre", true); return }
            val r = runCatching { p.appuie(CLIENT, false, "") }.getOrElse { it.message ?: "erreur" }
            if (r != null) { arrete("PTT refusé : $r", true); return }
        }
        val maintenant = horloge()
        val debutSon = maxOf(debutTranche + Ft8Qso.DEBUT_MS, maintenant + (if (sim) 0L else DELAI_PTT_MS))
        enCours = Emission(texte, qso.prochain, n, debutTranche, pcm, debutSon, sim, frequenceHz)
        erreur = ""
    }

    private fun poursuit(e: Emission, t: Long) {
        if (!e.simule) {
            val p = poste!!
            p.vie(CLIENT)
            // Released by another guard (time-out, link lost, LEILA, STOP from the header): this over is over.
            if (!p.poste.tenuPar(CLIENT)) {
                arrete("PTT relâché : " + p.poste.etat.raison.ifBlank { "garde du poste" }, true)
                return
            }
        }
        if (t >= e.debutSonMs) {
            val voulu = (((t - e.debutSonMs + AVANCE_SON_MS) * cadence) / 1000).toInt().coerceAtMost(e.pcm.size)
            while (e.pousses < voulu) {
                val k = minOf(voulu - e.pousses, BLOC)
                val bloc = e.pcm.copyOfRange(e.pousses, e.pousses + k)
                if (!e.simule) poste!!.sonInterne(CLIENT, bloc, k, cadence)?.let { err ->
                    arrete("son vers le poste : $err", true); return
                }
                e.pousses += k
            }
        }
        if (e.pousses >= e.pcm.size) {
            if (e.finSonMs == 0L) e.finSonMs = t
            // The card's cushion played out before the PTT is released.
            if (t - e.finSonMs >= QUEUE_MS) {
                enCours = null
                if (!e.simule) runCatching { poste?.relache("fin du message FT8/FT4") }
                envois++
                dernier = Triple(e.texte, e.debutTrancheMs, e.hz)
                qso.envoye(e.tx, t)
                runCatching { auEnvoi?.invoke(e.texte, e.debutTrancheMs, e.hz) }
                if (qso.aEnvoyer() == null) arrete(if (qso.etape == Ft8Qso.Etape.FINI) "QSO terminé : à noter au carnet" else "plus rien à envoyer")
            }
        }
    }

    /** The loop (every 20 ms). */
    fun demarreBoucle() {
        thread(isDaemon = true, name = "satme-ft8-tx", priority = Thread.MAX_PRIORITY) {
            while (true) {
                runCatching { tic() }
                Thread.sleep(20)
            }
        }
    }

    // ------------------------------------------------------------ the page

    fun json(): JSONObject {
        val t = horloge()
        val e = enCours
        val l = runCatching { liaison() }.getOrDefault(Liaison())
        val d = decalageHorloge(t)
        val msgs = qso.messages()
        val n = Ft8Qso.tranche(t, ft4())
        // When our next transmission starts, for the page's countdown.
        val prochaine = if (!arme || e != null) null else {
            var k = n
            while (!(Ft8Qso.estPaire(k) == qso.pair && (k > n || (t - k * duree() <= Ft8Qso.debutMaxMs(ft4()) && derniereTranche != k)))) k++
            k * duree() + Ft8Qso.DEBUT_MS
        }
        val o = JSONObject().put("ok", true)
            .put("arme", arme).put("emet", e != null).put("simule", simule()).put("emetReel", emet)
            .put("mode", ft8.mode).put("ecoute", ft8.enMarche)
            .put("raison", raison).put("erreur", erreur)
            .put("moi", moi()).put("carre", carre().take(4))
            .put("etape", qso.etape.name).put("etapeTexte", qso.etape.libelle).put("dernier", qso.dernier)
            .put("partenaire", qso.partenaire ?: "").put("sonCarre", qso.sonCarre ?: "")
            .put("rapportEnvoye", qso.rapportEnvoye?.let { Ft8Qso.rapport(it) } ?: "").put("rapportRecu", qso.rapportRecu?.let { Ft8Qso.rapport(it) } ?: "")
            .put("prochain", qso.prochain).put("pair", qso.pair).put("pairCq", pairCq)
            .put("messages", JSONArray(msgs.mapIndexed { i, m -> JSONObject().put("n", i + 1).put("texte", m)
                .put("genere", qso.genere(i + 1)).put("type", Ft8Pliage.genre(m) ?: if (m.isBlank()) "" else "impossible") }))
            .put("hz", frequenceHz).put("tient", tient).put("auto", auto).put("niveau", niveauDb).put("rrr", rrr)
            .put("chienMin", chienMin).put("chienS", if (arme) qso.chienRestantMs(t) / 1000 else JSONObject.NULL)
            .put("prochaineMs", prochaine ?: JSONObject.NULL)
            .put("horloge", d?.let { Math.round(it * 10) / 10.0 } ?: JSONObject.NULL).put("horlogeMax", Ft8Qso.horlogeMaxS(ft4()))
            .put("refus", if (arme && e == null) "" else refusPermanent(t) ?: "")
            .put("envois", envois)
            .put("largeur", Ft8Qso.largeurHz(ft4()))
            .put("donnees", donnees).put("donneesPossible", modeDonnees?.possible() == true)
            .put("donneesActif", veutDonnees && modeDonnees?.possible() == true && modeDonnees.pret())
            .put("modePoste", modeDonnees?.modePoste() ?: "")
        if (e != null) o.put("enCours", JSONObject().put("texte", e.texte).put("tx", e.tx).put("hz", e.hz)
            .put("progres", if (e.pcm.isEmpty()) 1.0 else Math.round(e.pousses * 1000.0 / e.pcm.size) / 1000.0))
        dernier?.let { (tx, tr, hz) -> o.put("dernierEnvoi", JSONObject().put("texte", tx).put("t", tr).put("hz", hz)) }
        qso.aNoter?.let { f -> o.put("aNoter", JSONObject().put("indicatif", f.indicatif).put("carre", f.carre)
            .put("rs", f.rapportEnvoye).put("rr", f.rapportRecu).put("t", f.quandMs).put("satellite", l.satellite ?: "")) }
        // Where the signal goes: the uplink and where it comes back on the downlink.
        val ou = JSONObject().put("satellite", l.satellite ?: "").put("inverse", l.inverse)
        l.monteeHz?.let { ou.put("monteeHz", Ft8Montee.montee(it, frequenceHz, l.inverse)) }
        l.descenteHz?.let { ou.put("descenteHz", Ft8Montee.descente(it, frequenceHz)) }
        o.put("ou", ou)
        return o
    }

    /** The page's settings and actions (POST). */
    fun reglage(q: Map<String, String>): JSONObject {
        fun ok() = json()
        fun non(m: String) = json().put("ok", false).put("erreur", m)
        q["hz"]?.toIntOrNull()?.let { synchronized(this) { frequenceHz = it; qso.geste(horloge()) } }
        q["tient"]?.let { tient = it == "1"; geste() }
        q["auto"]?.let { auto = it == "1"; geste() }
        q["niveau"]?.toIntOrNull()?.let { niveauDb = it; geste() }
        q["chien"]?.toIntOrNull()?.let { chienMin = it; geste() }
        q["pair"]?.let { synchronized(this) { pairCq = it == "1"; if (qso.etape == Ft8Qso.Etape.CQ) qso.appelleCq(pairCq, horloge()) } }
        q["rrr"]?.let { rrr = it == "1"; geste() }
        q["donnees"]?.let { donnees = it == "1"; geste() }
        q["tx"]?.toIntOrNull()?.let { choisit(it) }
        for (i in 1..6) q["msg$i"]?.let { edite(i, it)?.let { m -> return non(m) } }
        if (q["oublie"] == "1") oublieFin()
        if (q["noter"] == "1") note(q["c"], q["l"], q["rs"], q["rr"], q["n"].orEmpty())?.let { return non(it) }
        return ok()
    }

    companion object {
        /** The PTT's client for SatMe's own FT8/FT4 audio. */
        const val CLIENT = "satme-ft8"
        const val BAS_HZ = 200
        const val HAUT_HZ = 2_900
        /** The PTT keyed this long before the audio (the rig's relay, its TX settling). */
        const val AVANCE_PTT_MS = 200L
        const val DELAI_PTT_MS = 150L
        /** The audio pushed this far ahead of the clock (the card's cushion is 60 ms). */
        const val AVANCE_SON_MS = 80L
        /** After the last sample: the card's cushion played before the PTT goes. */
        const val QUEUE_MS = 200L
        const val BLOC = 960
        /** The page silent longer than this: stopped. */
        const val VIE_MS = 15_000L
    }
}
