/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.cat.SerialLink
import fr.f4ioz.satcombo.demo.ModulesStation
import fr.f4ioz.satcombo.plateforme.Lignes
import fr.f4ioz.satcombo.plateforme.portPttRigExpert
import fr.f4ioz.satcombo.polarisation.Bande
import fr.f4ioz.satcombo.polarisation.Equipement
import fr.f4ioz.satcombo.polarisation.Esp32Virtuel
import fr.f4ioz.satcombo.polarisation.EtatBoitier
import fr.f4ioz.satcombo.polarisation.Pol
import fr.f4ioz.satcombo.polarisation.ProtocolePolarisation
import fr.f4ioz.satcombo.polarisation.ProtocolePolarisation.Reponse
import fr.f4ioz.satcombo.polarisation.Selecteur
import fr.f4ioz.satcombo.polarisation.SondeHv
import fr.f4ioz.satcombo.polarisation.StatsPolarisation
import fr.f4ioz.satcombo.polarisation.VerrouEmission
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * The antenna polarisation switch (module « Polarisation »): an ESP32 on a
 * USB serial port driving the WiMo boxes' relay trees
 * (`materiel/esp32-polarisation`), or the virtual ESP32 ([PORT_VIRTUEL])
 * with the same rules, until the box is built.
 *
 * **Nothing opens until a port is chosen** in Réglages › Poste et rotor;
 * lost, it is looked for again every few seconds (by its USB key, the
 * path may change). The front panel's selector has the last word: off
 * AUTO, SatMe only shows what it reads.
 *
 * **Never a command to an antenna that transmits**: neither while SatMe
 * transmits through it ([emissions]: its band from the frequency, both
 * when unknown) nor while the rig's SEND line is down (the box refuses
 * too). Receive only: no radio transmission ever comes from here.
 *
 * At AOS, each equipped band is put as the satellite's profile says (or
 * as the probes taught, [StatsPolarisation]). In AUTO, « sonder H/V »
 * tries H and V on the receiving antenna now and then ([SondeHv]) and
 * keeps each probe in `polarisation/mesures.jsonl`.
 */
class PolarisationPc(
    private val station: StationPc,
    dossier: File,
    private val horloge: () -> Long = System::currentTimeMillis,
    /** Opens the virtual ESP32 (the tests keep theirs). */
    private val fabriqueVirtuel: () -> Esp32Virtuel = { Esp32Virtuel() },
) {
    /** The transmissions SatMe holds now, each by its sky frequency (null: unknown, both antennas held). */
    @Volatile var emissions: () -> List<Long?> = { emptyList() }
    /** An SSTV picture being decoded: no probe would spoil it. */
    @Volatile var sstvEnCours: () -> Boolean = { false }
    /** The SDR's channel level (dB) while it listens to the satellite followed, else null. */
    @Volatile var niveauSdr: () -> Double? = { null }

    private val st = station.stockage

    // ------------------------------------------------------------ settings

    /** The port's USB key, [PORT_VIRTUEL], or empty: none (nothing opened). */
    var port: String
        get() = st.getString("pc_pol_port", "") ?: ""
        set(v) { st.edit().putString("pc_pol_port", v.trim()).apply() }
    var uhf: Equipement
        get() = Equipement.de(st.getString("pc_pol_uhf", null))?.takeIf { it != Equipement.HV } ?: Equipement.WIMO
        set(v) { st.edit().putString("pc_pol_uhf", v.name).apply() }
    var vhf: Equipement
        get() = Equipement.de(st.getString("pc_pol_vhf", null)) ?: Equipement.AUCUN
        set(v) { st.edit().putString("pc_pol_vhf", v.name).apply() }
    /** Right and left swapped between SatMe and the box (the sense depends on the wiring). */
    var inverse: Boolean
        get() = st.getBoolean("pc_pol_inverse", false)
        set(v) { st.edit().putBoolean("pc_pol_inverse", v).apply() }
    /** Put each band as the satellite's profile says at AOS. */
    var aos: Boolean
        get() = st.getBoolean("pc_pol_aos", true)
        set(v) { st.edit().putBoolean("pc_pol_aos", v).apply() }
    var sondeOn: Boolean
        get() = st.getBoolean("pc_pol_sonde", false)
        set(v) { st.edit().putBoolean("pc_pol_sonde", v).apply() }
    var periodeS: Int
        get() = st.getInt("pc_pol_periode", 30).coerceIn(20, 60)
        set(v) { st.edit().putInt("pc_pol_periode", v.coerceIn(20, 60)).apply() }
    /** Hysteresis, tenths of a dB (1 to 6 dB). */
    var hystDixiemes: Int
        get() = st.getInt("pc_pol_hyst", 25).coerceIn(10, 60)
        set(v) { st.edit().putInt("pc_pol_hyst", v.coerceIn(10, 60)).apply() }
    /** Where the level comes from: AUTO (the S-meter, else the SDR), SMETRE, SDR. */
    var source: String
        get() = st.getString("pc_pol_source", "AUTO")?.takeIf { it in SOURCES } ?: "AUTO"
        set(v) { if (v in SOURCES) st.edit().putString("pc_pol_source", v).apply() }
    /** Virtual ESP32 only: a made-up pass (and level) to see the probe at work. */
    @Volatile var simPassage = false
    @Volatile private var simDebutMs = 0L

    fun equipement(b: Bande): Equipement = if (b == Bande.U) uhf else vhf

    // ------------------------------------------------------------ per satellite

    /** What to do at AOS for [catnum] on [b]: null nothing, else a polarisation or [PREDITE]. */
    fun profil(catnum: Int, b: Bande): String = runCatching { JSONObject(st.getString("pc_pol_profil_$catnum", null) ?: "{}") }.getOrNull()
        ?.optString(b.name, PREDITE)?.takeIf { it == PREDITE || it == RIEN || Pol.de(it) != null } ?: PREDITE

    fun poseProfil(catnum: Int, b: Bande, v: String) {
        val o = runCatching { JSONObject(st.getString("pc_pol_profil_$catnum", null) ?: "{}") }.getOrDefault(JSONObject())
        o.put(b.name, if (v == PREDITE || v == RIEN || Pol.de(v) != null) v.uppercase() else PREDITE)
        st.edit().putString("pc_pol_profil_$catnum", o.toString()).apply()
    }

    // ------------------------------------------------------------ the probes kept

    val fichier = File(dossier, "polarisation/mesures.jsonl")
    private val mesures: MutableList<StatsPolarisation.Mesure> by lazy {
        runCatching { fichier.useLines { StatsPolarisation.litFichier(it) } }.getOrDefault(emptyList()).toMutableList()
    }

    fun mesures(): List<StatsPolarisation.Mesure> = synchronized(mesures) { mesures.toList() }

    /** The probes a prediction may use: the made-up ones only while the virtual ESP32 is in use. */
    private fun mesuresUtiles(): List<StatsPolarisation.Mesure> = mesures().filter { it.source != SIMULEE || port == PORT_VIRTUEL }

    private fun garde(m: StatsPolarisation.Mesure) {
        synchronized(mesures) { mesures += m }
        runCatching { fichier.parentFile?.mkdirs(); fichier.appendText(StatsPolarisation.ligneFichier(m) + "\n") }
    }

    /** Forgets the made-up probes ([seulementSimulees]) or all of them. */
    fun efface(seulementSimulees: Boolean) {
        synchronized(mesures) {
            if (seulementSimulees) mesures.removeAll { it.source == SIMULEE } else mesures.clear()
            runCatching { fichier.parentFile?.mkdirs(); fichier.writeText(mesures.joinToString("") { StatsPolarisation.ligneFichier(it) + "\n" }) }
        }
    }

    // ------------------------------------------------------------ the line

    private val verrou = Any()
    @Volatile private var lien: SerialLink? = null
    @Volatile private var ouvertSur = ""
    /** The virtual ESP32, kept between two connections (its selector and SEND stay where they were put). */
    @Volatile var virtuel: Esp32Virtuel? = null; private set
    /** « coupe » (no port), « attente » (looking for it, or no answer yet), « connecte », « erreur ». */
    @Volatile var connexion = "coupe"; private set
    @Volatile var message = ""; private set
    @Volatile var ident = ""; private set
    /** The box as it last said (physical polarisations: right and left as wired). */
    @Volatile var etat: EtatBoitier? = null; private set
    @Volatile private var ouvertMs = 0L
    @Volatile private var essaiMs = Long.MIN_VALUE / 2
    @Volatile private var recuMs = 0L
    @Volatile private var demandeMs = 0L
    @Volatile private var idDemande = false
    private val tampon = StringBuilder()
    @Volatile private var reponse: Reponse? = null
    private val journal = ArrayDeque<String>()

    private fun note(sens: String, l: String) = synchronized(journal) {
        journal.addLast(String.format("%tT %s %s", horloge(), sens, l)); while (journal.size > 40) journal.removeFirst()
    }

    private fun ferme(pourquoi: String) {
        synchronized(verrou) {
            lien?.let { l -> runCatching { if (l !is Esp32Virtuel) l.close() } }
            lien = null; ouvertSur = ""; etat = null; tampon.setLength(0)
            if (pourquoi.isNotBlank()) message = pourquoi
        }
    }

    /** The port with key [cle] opened, else why not. */
    private fun ouvre(cle: String): String? {
        if (cle == PORT_VIRTUEL) {
            val v = virtuel ?: fabriqueVirtuel().also { virtuel = it }
            lien = v; ouvertSur = cle; ouvertMs = horloge(); recuMs = 0L; idDemande = false
            return null
        }
        val p = station.ports.liste().firstOrNull { it.cle == cle } ?: return "boîtier introuvable (débranché ?)"
        if (p.portPttRigExpert() || station.portDAutrePoste(p)) return "${station.nomPort(p)} est l'interface d'un poste : jamais ouvert ici"
        if (p.cle in station.clesPoste()) return "c'est le port du poste (CAT)"
        if (station.stationRotor.etat.value.rotorConnected && p.cle == station.settings.rotorUsbCle) return "c'est le port du rotor"
        if (p.cle == station.settings.aprsKissCle && p.cle.isNotBlank()) return "c'est le port de la radio KISS"
        // RTS and DTR low: an ESP32 DevKit runs (EN high, GPIO0 high); Linux may still reset it while opening — it starts with every relay at rest.
        val o = runBlocking { station.ports.ouvre(p, ProtocolePolarisation.VITESSE, Lignes.BASSES) }
        val l = o.lien ?: return o.erreur.ifBlank { "ouverture refusée" }
        lien = l; ouvertSur = cle; ouvertMs = horloge(); recuMs = 0L; idDemande = false
        return null
    }

    private fun ecris(ligne: String): Boolean {
        val l = lien ?: return false
        note("→", ligne)
        val ok = runCatching { l.write((ligne + "\n").toByteArray(Charsets.UTF_8), 300) }.getOrDefault(false)
        if (!ok) { ferme("écriture impossible : boîtier débranché ?"); connexion = "attente" }
        return ok
    }

    /** Reads what came (up to [attenteMs]), each line taken in. */
    private fun lis(attenteMs: Int) {
        val l = lien ?: return
        val b = ByteArray(256)
        val n = runCatching { l.read(b, attenteMs) }.getOrDefault(0)
        if (n <= 0) return
        tampon.append(String(b, 0, n, Charsets.UTF_8))
        while (true) {
            val i = tampon.indexOfFirst { it == '\n' || it == '\r' }
            if (i < 0) break
            val ligne = tampon.substring(0, i).trim(); tampon.delete(0, i + 1)
            if (ligne.isNotEmpty()) prends(ligne)
        }
        if (tampon.length > 512) tampon.setLength(0)
    }

    private fun prends(ligne: String) {
        note("←", ligne)
        val r = ProtocolePolarisation.lit(ligne) ?: return
        when (r) {
            is Reponse.Etat -> { etat = r.etat; recuMs = horloge(); connexion = "connecte" }
            is Reponse.Id -> { ident = r.texte; recuMs = horloge(); connexion = "connecte" }
            is Reponse.Ok, is Reponse.Err -> { reponse = r; recuMs = horloge() }
            is Reponse.Autre -> {}
        }
    }

    /** [ligne] sent and its OK/ERR awaited (1.5 s); null when OK, else why not. */
    private fun echange(ligne: String): String? = synchronized(verrou) {
        if (lien == null) return "boîtier non connecté"
        reponse = null
        if (!ecris(ligne)) return "écriture impossible"
        val fin = horloge() + 1_500L
        while (horloge() < fin) {
            lis(30)
            when (val r = reponse) {
                is Reponse.Ok -> return null
                is Reponse.Err -> return when (r.code) {
                    "MANUEL" -> "sélecteur en manuel : le boîtier ne prend pas d'ordre"
                    "TX" -> "émission en cours (SEND) : pas de commutation"
                    else -> "refusé : ${r.code} ${r.texte}".trim()
                }
                else -> {}
            }
            if (lien == null) return "liaison perdue"
        }
        "pas de réponse du boîtier"
    }

    /** The antennas SatMe transmits through now. */
    fun bandesBloquees(): Set<Bande> = runCatching { VerrouEmission.bandes(emissions()) }.getOrDefault(Bande.entries.toSet())

    /** Why [p] cannot go to [b] now, null when it can. */
    fun refus(b: Bande, p: Pol): String? {
        if (!ModulesStation.actif(st, MODULE)) return "module Polarisation désactivé"
        val e = etat ?: return if (port.isBlank()) "aucun boîtier choisi (Réglages › Poste et rotor)" else "boîtier non connecté"
        val eq = equipement(b)
        if (eq == Equipement.AUCUN) return "rien à commuter en ${b.nom} (Réglages)"
        if (!eq.permet(p)) return "${b.nom} : ${eq.nom}, ${p.nom} impossible"
        if (e.selecteur.manuel) return "sélecteur en manuel : c'est lui qui décide"
        if (b in bandesBloquees()) return "SatMe émet sur l'antenne ${b.nom} : pas de commutation"
        if (e.tx) return "émission en cours (SEND) : pas de commutation"
        return null
    }

    /** [p] on [b] (as SatMe names it: right and left swapped here when wired the other way); null when done. */
    fun commande(b: Bande, p: Pol): String? {
        refus(b, p)?.let { return it }
        if (etat?.pol(b)?.inverse(inverse) == p) return null
        return echange(ProtocolePolarisation.commande(b, p.inverse(inverse))).also { r -> message = r ?: "${b.nom} : ${p.nom}" }
    }

    /** « Tester la liaison »: who answers and what it says, timed. */
    fun teste(): JSONObject {
        if (lien == null) return JSONObject().put("ok", false).put("erreur", if (port.isBlank()) "aucun port choisi" else message.ifBlank { "boîtier non connecté" })
        val t0 = horloge()
        synchronized(verrou) {
            ident = ""; etat = null
            ecris(ProtocolePolarisation.ID); ecris(ProtocolePolarisation.ETAT)
            val fin = t0 + 2_000L
            while (horloge() < fin && (ident.isBlank() || etat == null) && lien != null) lis(30)
        }
        val e = etat
        val ms = horloge() - t0
        return if (ident.isBlank() && e == null) JSONObject().put("ok", false).put("erreur", "pas de réponse en 2 s (bon port ? 115200 bauds ? firmware chargé ?)")
        else JSONObject().put("ok", true).put("ms", ms).put("texte", (ident.ifBlank { "réponse sans identification" }) +
            (e?.let { " · sélecteur ${it.selecteur.code} · UHF ${it.u.inverse(inverse).court} · VHF ${it.v.inverse(inverse).court}" + if (it.tx) " · SEND actif" else "" } ?: "") +
            " · $ms ms")
    }

    // ------------------------------------------------------------ the loop

    @Volatile private var ticMs = 0L
    @Volatile private var fil: Thread? = null

    fun demarre() {
        if (fil != null) return
        fil = Thread({
            while (true) {
                runCatching { pas(horloge()) }
                Thread.sleep(40)
            }
        }, "satme-polarisation").apply { isDaemon = true; start() }
    }

    fun arrete() = ferme("")

    /** One turn: the port opened, closed or found again; what came read; the AOS and the probe looked after. */
    fun pas(t: Long) {
        val voulu = if (ModulesStation.actif(st, MODULE)) port else ""
        if (voulu.isBlank()) { if (lien != null) ferme(""); connexion = "coupe"; message = ""; return }
        if (lien != null && ouvertSur != voulu) ferme("")
        if (lien == null) {
            connexion = "attente"
            if (t - essaiMs < 5_000L) return
            essaiMs = t
            synchronized(verrou) { ouvre(voulu) }?.let { message = it; return }
            message = "ouvert, en attente de l'ESP32…"
        }
        synchronized(verrou) {
            lis(20)
            // A DevKit restarts as its port opens: asked once it had time to boot, then every 10 s.
            if (!idDemande && t - ouvertMs >= DEMARRAGE_MS) { idDemande = true; ecris(ProtocolePolarisation.ID); ecris(ProtocolePolarisation.ETAT); demandeMs = t }
            if (idDemande && t - demandeMs >= 10_000L) { demandeMs = t; ecris(ProtocolePolarisation.ETAT) }
        }
        if (lien != null && t - ouvertMs > 20_000L && t - maxOf(recuMs, ouvertMs) > 25_000L) {
            ferme("plus de réponse du boîtier : nouvel essai"); connexion = "attente"; return
        }
        if (connexion == "connecte" && message.startsWith("ouvert")) message = ""
        if (t - ticMs >= 250L) { ticMs = t; runCatching { controle(t) } }
    }

    // ------------------------------------------------------------ AOS and probe

    /** Where the satellite is for the profile and the probe: the one followed (or the made-up pass of the virtual ESP32). */
    data class Contexte(val catnum: Int, val nom: String, val elDeg: Double, val azDeg: Double, val aosMs: Long, val losMs: Long,
                        val rx: Bande?, val mode: String, val simule: Boolean)

    fun contexte(t: Long): Contexte? {
        val e = station.etat.value
        val s = e.suivi
        val tx = station.emetteurChoisi()
        val rx = Bande.deFrequence(tx?.downlinkLowHz)
        if (simPassage && port == PORT_VIRTUEL) {
            if (simDebutMs == 0L) simDebutMs = t
            val cycle = SIM_PASSAGE_MS + SIM_PAUSE_MS
            val dans = (t - simDebutMs) % cycle
            val aos = t - dans
            val f = dans.toDouble() / SIM_PASSAGE_MS
            val el = if (f <= 1.0) 62.0 * sin(PI * f) else -5.0
            return Contexte(s?.catalogNumber ?: 99_999, s?.name ?: "SIM-1", el, (150.0 + 120.0 * f) % 360.0, aos, aos + SIM_PASSAGE_MS,
                rx ?: Bande.U, tx?.mode.orEmpty(), true)
        }
        simDebutMs = 0L
        s ?: return null
        val p = e.position ?: return null
        val (aos, los) = station.passageEnCours(s.catalogNumber, t) ?: return null
        return Contexte(s.catalogNumber, s.name, p.elevationDeg, p.azimuthDeg, aos, los, rx, tx?.mode.orEmpty(), false)
    }

    val sonde = SondeHv()
    /** The pass whose profile went out (« catnum:aos »), and the last word on it. */
    @Volatile private var passeAppliquee = ""
    @Volatile var aosTexte = ""; private set
    @Volatile var sondePourquoi = ""; private set
    @Volatile private var sourceUtilisee = ""

    /** What the profile asks for [b] now, null: nothing. */
    fun cible(c: Contexte, b: Bande): Pol? {
        val eq = equipement(b)
        if (eq == Equipement.AUCUN) return null
        val v = profil(c.catnum, b)
        val p = when (v) {
            RIEN -> null
            PREDITE -> StatsPolarisation.prediction(mesuresUtiles(), c.catnum, b, c.elDeg)?.pol
            else -> Pol.de(v)
        }
        return p?.takeIf { eq.permet(it) }
    }

    private fun controle(t: Long) {
        val e = etat ?: return
        val c = contexte(t)
        val cle = c?.let { "${it.catnum}:${it.aosMs}" }.orEmpty()
        if (c == null || c.elDeg < -2.0) { passeAppliquee = ""; sondePourquoi = "pas de passage en cours"; sonde.tic(t, false, false, null, null); return }
        // At AOS (or a satellite chosen during a pass): the profile, once a pass, when the box takes orders.
        if (aos && cle != passeAppliquee) {
            val bloquees = bandesBloquees()
            val voulus = Bande.entries.mapNotNull { b -> cible(c, b)?.let { b to it } }
            when {
                voulus.isEmpty() -> { passeAppliquee = cle; aosTexte = "" }
                e.selecteur.manuel -> aosTexte = "profil de ${c.nom} en attente : sélecteur en manuel"
                e.tx || voulus.any { it.first in bloquees } -> aosTexte = "profil de ${c.nom} en attente : émission en cours"
                else -> {
                    val r = voulus.map { (b, p) -> b to commande(b, p) }
                    if (r.all { it.second == null }) {
                        passeAppliquee = cle; sonde.oublie()
                        aosTexte = "AOS de ${c.nom} : " + voulus.joinToString(", ") { "${it.first.nom} ${it.second.court}" }
                    } else aosTexte = r.firstNotNullOf { it.second }
                }
            }
        }
        val rx = c.rx
        val eqRx = rx?.let { equipement(it) } ?: Equipement.AUCUN
        sondePourquoi = when {
            !sondeOn -> "sondage coupé"
            rx == null -> "bande de réception inconnue (choisir un émetteur)"
            eqRx == Equipement.AUCUN -> "rien à commuter en ${rx.nom}"
            c.elDeg < 0.0 -> "satellite sous l'horizon"
            e.selecteur.manuel -> "sélecteur en manuel"
            sstvEnCours() -> "image SSTV en cours de décodage"
            else -> ""
        }
        val actif = sondePourquoi.isEmpty()
        val peut = rx != null && !e.tx && rx !in bandesBloquees() && !e.selecteur.manuel
        sonde.periodeMs = periodeS * 1000L
        sonde.hystDb = hystDixiemes / 10.0
        val actuelle = rx?.let { e.pol(it).inverse(inverse) }
        val m = if (actif) mesure(t, c, actuelle) else null
        if (actif && m == null && sonde.phase == SondeHv.Phase.REPOS) sondePourquoi = "aucune mesure du signal (S-mètre du poste, ou SDR sur le satellite)"
        val a = sonde.tic(t, actif && m != null || sonde.phase != SondeHv.Phase.REPOS && actif, peut, actuelle, m)
        if (rx != null) a.commande?.let { p -> commande(rx, p)?.let { message = it } }
        a.abandon?.let { message = "sondage H/V : $it" }
        a.resultat?.let { r ->
            garde(StatsPolarisation.Mesure(t, c.catnum, c.nom, c.mode, rx!!, c.elDeg, c.azDeg, StatsPolarisation.phase(t, c.aosMs, c.losMs),
                r.hDb, r.vDb, r.gagnante, sourceUtilisee))
            message = "sondage H/V : H ${"%.1f".format(r.hDb)} dB, V ${"%.1f".format(r.vDb)} dB → ${r.gagnante.court}"
        }
    }

    /** The level now, from the source chosen; the virtual ESP32's made-up pass gives its own. */
    private fun mesure(t: Long, c: Contexte, actuelle: Pol?): SondeHv.Mesure? {
        if (c.simule) { sourceUtilisee = SIMULEE; return niveauSimule(t, c, actuelle) }
        val sm = if (source != "SDR") station.smetreLu()?.let { (v, ms) -> SondeHv.Mesure(SondeHv.smetreDb(v), ms) } else null
        if (sm != null) { sourceUtilisee = "smetre"; return sm }
        val sdr = if (source != "SMETRE") niveauSdr()?.let { SondeHv.Mesure(it, t) } else null
        if (sdr != null) { sourceUtilisee = "sdr"; return sdr }
        return null
    }

    private val hasard = java.util.Random()

    /** A made-up level: H better low on the horizon, V higher up, a little noise. Virtual ESP32 only. */
    private fun niveauSimule(t: Long, c: Contexte, p: Pol?): SondeHv.Mesure {
        val base = 20.0 + c.elDeg * 0.3
        val avantageH = if (c.elDeg < 25.0) 4.0 else -3.0
        val d = when (p) { Pol.H -> avantageH / 2; Pol.V -> -avantageH / 2; else -> -1.0 }
        return SondeHv.Mesure(base + d + hasard.nextGaussian() * 0.8, t)
    }

    // ------------------------------------------------------------ the page

    /** The virtual ESP32's front panel moved from the page: [sel] the selector, [send] the rig's SEND line, [passage] a made-up pass. */
    fun simule(sel: String?, send: String?, passage: String?): JSONObject {
        if (port != PORT_VIRTUEL) return JSONObject().put("ok", false).put("erreur", "seulement avec l'ESP32 virtuel")
        val v = virtuel ?: return JSONObject().put("ok", false).put("erreur", "ESP32 virtuel pas encore ouvert")
        // The selector's positions as engraved on the front panel (wired as the box: no swapping here).
        sel?.let { Selecteur.de(it) }?.let { v.tourne(it) }
        send?.let { v.emission(it == "1") }
        passage?.let { simPassage = it == "1"; simDebutMs = 0L; sonde.oublie() }
        synchronized(verrou) { lis(30) }
        return json()
    }

    fun ports(): JSONArray = JSONArray().apply {
        put(JSONObject().put("cle", PORT_VIRTUEL).put("nom", "ESP32 virtuel (essai, démonstration)"))
        runCatching { station.ports.liste() }.getOrDefault(emptyList()).forEach { p -> put(JSONObject().put("cle", p.cle).put("nom", station.nomPort(p))) }
    }

    fun json(): JSONObject {
        val e = etat
        val t = horloge()
        val c = runCatching { contexte(t) }.getOrNull()
        val bloquees = bandesBloquees()
        val o = JSONObject().put("ok", true).put("module", ModulesStation.actif(st, MODULE)).put("port", port).put("virtuel", port == PORT_VIRTUEL)
            .put("connexion", connexion).put("message", message).put("ident", ident)
            .put("uhf", uhf.name).put("vhf", vhf.name).put("inverse", inverse).put("aos", aos).put("aosTexte", aosTexte)
            .put("bloquees", JSONArray(bloquees.map { it.name }))
        o.put("etat", e?.let {
            JSONObject().put("sel", it.selecteur.code).put("manuel", it.selecteur.manuel).put("tx", it.tx).put("fw", it.fw)
                .put("U", it.u.inverse(inverse).name).put("V", it.v.inverse(inverse).name)
        } ?: JSONObject.NULL)
        o.put("bandes", JSONArray().apply {
            Bande.entries.filter { equipement(it) != Equipement.AUCUN }.forEach { b ->
                put(JSONObject().put("b", b.name).put("nom", b.nom).put("equipement", equipement(b).name)
                    .put("pols", JSONArray(Pol.entries.filter { equipement(b).permet(it) }.map { it.name }))
                    .put("bloquee", b in bloquees).put("refus", Pol.entries.firstOrNull { equipement(b).permet(it) }?.let { refus(b, it) } ?: JSONObject.NULL))
            }
        })
        o.put("sat", c?.let { k ->
            val utiles = mesuresUtiles()
            JSONObject().put("catnum", k.catnum).put("nom", k.nom).put("el", Math.round(k.elDeg * 10) / 10.0).put("az", Math.round(k.azDeg))
                .put("rx", k.rx?.name ?: JSONObject.NULL).put("mode", k.mode).put("simule", k.simule)
                .put("phase", StatsPolarisation.phase(t, k.aosMs, k.losMs))
                .put("profil", JSONObject().apply { Bande.entries.forEach { b -> put(b.name, profil(k.catnum, b)) } })
                .put("prediction", JSONObject().apply { Bande.entries.forEach { b ->
                    put(b.name, StatsPolarisation.prediction(utiles, k.catnum, b, k.elDeg)?.texte() ?: JSONObject.NULL) } })
                .put("stats", lignes(StatsPolarisation.tableau(utiles.filter { it.catnum == k.catnum })))
        } ?: suiviSansPassage())
        val d = sonde.dernier
        o.put("sonde", JSONObject().put("on", sondeOn).put("periode", periodeS).put("hyst", hystDixiemes / 10.0).put("source", source)
            .put("phase", sonde.phase.name).put("pourquoi", sondePourquoi)
            .put("dernier", d?.let { JSONObject().put("h", Math.round(it.hDb * 10) / 10.0).put("v", Math.round(it.vDb * 10) / 10.0)
                .put("gagnante", it.gagnante.name).put("t", it.tMs) } ?: JSONObject.NULL))
        o.put("nMesures", mesures().size)
        o.put("journal", JSONArray(synchronized(journal) { journal.toList().takeLast(12) }))
        if (port == PORT_VIRTUEL) o.put("sim", JSONObject().put("passage", simPassage)
            .put("sel", virtuel?.selecteur?.code ?: "AUTO").put("send", virtuel?.send == true))
        return o
    }

    /** The satellite followed between two passes: its profile can still be set. */
    private fun suiviSansPassage(): Any {
        val s = station.etat.value.suivi ?: return JSONObject.NULL
        return JSONObject().put("catnum", s.catalogNumber).put("nom", s.name).put("horsPassage", true)
            .put("rx", Bande.deFrequence(station.emetteurChoisi()?.downlinkLowHz)?.name ?: JSONObject.NULL)
            .put("profil", JSONObject().apply { Bande.entries.forEach { b -> put(b.name, profil(s.catalogNumber, b)) } })
            .put("prediction", JSONObject()).put("stats", lignes(StatsPolarisation.tableau(mesuresUtiles().filter { it.catnum == s.catalogNumber })))
    }

    private fun lignes(l: List<StatsPolarisation.Ligne>): JSONArray = JSONArray(l.map { k ->
        JSONObject().put("cat", k.catnum).put("sat", k.satellite).put("bande", k.bande.name).put("tranche", StatsPolarisation.nomTranche(k.tranche))
            .put("n", k.bilan.n).put("h", k.bilan.h).put("v", k.bilan.v).put("ecart", Math.round(k.bilan.ecartMoyenDb * 10) / 10.0)
            .put("meilleure", if (k.bilan.predit) k.bilan.meilleure.name else JSONObject.NULL).put("confiance", (k.bilan.confiance * 100).toInt())
    })

    /** Every satellite's table and the last probes (Réglages). */
    fun stats(): JSONObject {
        val l = mesures()
        return JSONObject().put("ok", true).put("n", l.size).put("simulees", l.count { it.source == SIMULEE })
            .put("tableau", lignes(StatsPolarisation.tableau(mesuresUtiles())))
            .put("recentes", JSONArray(l.takeLast(15).reversed().map { it.json() }))
    }

    /** The API: GET reads, POST acts (never a transmission). */
    fun sert(chemin: String, methode: String, q: Map<String, String>): JSONObject {
        if (methode != "POST") return when (chemin) {
            "/api/polar/stats" -> stats()
            "/api/polar/ports" -> JSONObject().put("ok", true).put("ports", ports()).put("port", port)
            else -> json()
        }
        return when (chemin) {
            "/api/polar/reglage" -> {
                q["port"]?.let { port = it }
                q["uhf"]?.let { Equipement.de(it) }?.takeIf { it != Equipement.HV }?.let { uhf = it }
                q["vhf"]?.let { Equipement.de(it) }?.let { vhf = it }
                q["inverse"]?.let { inverse = it == "1" }
                q["aos"]?.let { aos = it == "1" }
                q["sonde"]?.let { sondeOn = it == "1"; if (sondeOn) sonde.maintenant() }
                q["periode"]?.toIntOrNull()?.let { periodeS = it }
                q["hyst"]?.replace(',', '.')?.toDoubleOrNull()?.let { hystDixiemes = Math.round(it * 10).toInt() }
                q["source"]?.let { source = it.uppercase() }
                essaiMs = Long.MIN_VALUE / 2
                json()
            }
            "/api/polar/pol" -> {
                val b = Bande.de(q["b"]) ?: return JSONObject().put("ok", false).put("erreur", "bande ?")
                val p = Pol.de(q["p"]) ?: return JSONObject().put("ok", false).put("erreur", "polarisation ?")
                val r = commande(b, p)
                json().put("ok", r == null).put("erreur", r ?: "")
            }
            "/api/polar/test" -> teste()
            "/api/polar/profil" -> {
                val cat = q["cat"]?.toIntOrNull() ?: return JSONObject().put("ok", false).put("erreur", "satellite ?")
                val b = Bande.de(q["b"]) ?: return JSONObject().put("ok", false).put("erreur", "bande ?")
                poseProfil(cat, b, q["p"].orEmpty().uppercase())
                json()
            }
            "/api/polar/sim" -> simule(q["sel"], q["send"], q["passage"])
            "/api/polar/sonde" -> { sonde.maintenant(); json() }
            "/api/polar/efface" -> { efface(q["tout"] != "1"); stats() }
            else -> JSONObject().put("ok", false).put("erreur", "inconnu")
        }
    }

    companion object {
        const val MODULE = "polar"
        const val PORT_VIRTUEL = "virtuel"
        const val PREDITE = "PREDITE"
        const val RIEN = "RIEN"
        const val SIMULEE = "simulee"
        val SOURCES = setOf("AUTO", "SMETRE", "SDR")
        /** An ESP32 DevKit restarted by the opening of its port boots in about a second. */
        const val DEMARRAGE_MS = 1_500L
        const val SIM_PASSAGE_MS = 6 * 60_000L
        const val SIM_PAUSE_MS = 90_000L
    }
}
