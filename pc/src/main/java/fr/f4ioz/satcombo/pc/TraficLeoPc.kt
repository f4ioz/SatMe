/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.aprs.Afsk
import fr.f4ioz.satcombo.aprs.AprsEmission
import fr.f4ioz.satcombo.aprs.Ax25
import fr.f4ioz.satcombo.aprs.EmissionKiss
import fr.f4ioz.satcombo.aprs.Trame
import fr.f4ioz.satcombo.data.Transmitter
import fr.f4ioz.satcombo.demo.ModulesStation
import fr.f4ioz.satcombo.domain.TraficSatellite
import fr.f4ioz.satcombo.domain.TraficSatellite.Genre
import fr.f4ioz.satcombo.sdr.ManipulateurCw
import fr.f4ioz.satcombo.telemesure.LectureWav
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * « Trafic satellite » on SatMe-PC (the module of that name, off until the
 * owner switches it on): working low-orbit satellites through the IC-9700
 * SatMe already keys ([EmissionPostePc]).
 *
 *  - a preset per satellite: its access tone (automatic, none, chosen), the
 *    lowest elevation the rig may transmit at, a power applied on demand;
 *  - SO-50's arming: 74.4 Hz armed by the Doppler loop, a 2.5 s carrier,
 *    the satellite's tone put back;
 *  - CW memories, sent as a keyed tone through the rig's audio (USB/LSB on
 *    a linear transponder: what comes back is heard at the same pitch);
 *  - voice memories (a short recording from the page), played once;
 *  - APRS through a satellite digipeater (path ARISS): a position, a
 *    message, a status, and the position beacon **armed for one pass**;
 *  - the guards added to every keying of the rig while the module is on.
 *
 * **Nothing transmits by itself.** Each over starts from an operator's
 * click on the page (through the one TX controller); the beacon sends only
 * inside the pass it was armed for, at most once a minute and a few times,
 * and only while the page says it is there. The demonstration simulates:
 * the audio is made and counted, the rig never keyed. Every guard of the
 * rig's PTT still applies (link, network authorisation, time-out, power
 * cap, QO-100's rules): this goes through it, never around it.
 */
class TraficLeoPc(
    private val stockage: fr.f4ioz.satcombo.plateforme.Stockage,
    /** The rig's TX; null: nothing can be keyed (the tests of the logic alone). */
    private val poste: EmissionPostePc?,
    /** Where the voice memories are kept. */
    private val dossier: File,
    private val situation: () -> Situation,
    private val moi: () -> String,
    private val locator: () -> String,
    /** The sender of APRS frames (callsign and SSID), and where the station is (lat, lon), approximate when chosen. */
    private val sourceAprs: () -> String = moi,
    private val positionAprs: () -> Pair<Double, Double>? = { null },
    /** A frame sent: kept in the APRS tab as sent. */
    private val aprsEmis: (Trame, Long) -> Unit = { _, _ -> },
    /** The Doppler loop: told to arm again (a new tone), and the tone it armed with how many armings so far. */
    private val rearme: () -> Unit = {},
    private val tonArme: () -> Pair<Int, Long> = { -1 to 0L },
    private val horloge: () -> Long = System::currentTimeMillis,
    /** True: simulated (the demonstration), never a key. */
    private val simule: () -> Boolean = { ModeDemo.actif },
    private val module: () -> Boolean = { ModulesStation.actif(stockage, MODULE) },
    val cadence: Int = 48_000,
) {
    /** Where the satellite followed is, and what the loop does with it. */
    data class Situation(
        val catnum: Int? = null,
        val nom: String = "",
        val elevationDeg: Double? = null,
        val transpondeur: Transmitter? = null,
        /** The uplink the Doppler loop sets (sky Hz, Doppler included), null when it does not. */
        val monteeHz: Long? = null,
        /** The pass under way, or the next one: AOS, LOS, highest elevation. */
        val aosMs: Long? = null,
        val losMs: Long? = null,
        val elevationMaxDeg: Double? = null,
        /** QO-100 (geostationary): another panel's business, none of these guards. */
        val geo: Boolean = false,
    )

    // ------------------------------------------------------------ settings (parameters only)

    fun actif(): Boolean = runCatching { module() }.getOrDefault(false)

    fun preset(catnum: Int?): TraficSatellite.Preset =
        if (catnum == null) TraficSatellite.Preset() else TraficSatellite.lit(stockage.getString(CLE_PRESET + catnum, null))

    private fun posePreset(catnum: Int, p: TraficSatellite.Preset) {
        stockage.edit().putString(CLE_PRESET + catnum, p.ecrit()).apply()
    }

    var wpm: Int
        get() = stockage.getInt("pc_trafic_wpm", 18).coerceIn(8, 35)
        set(v) { stockage.edit().putInt("pc_trafic_wpm", v.coerceIn(8, 35)).apply() }
    var tonCw: Int
        get() = stockage.getInt("pc_trafic_ton", 700).coerceIn(400, 1200)
        set(v) { stockage.edit().putInt("pc_trafic_ton", v.coerceIn(400, 1200)).apply() }
    /** The audio level sent to the rig, dBFS of the peak (−30 to −1). */
    var niveauDb: Int
        get() = stockage.getInt("pc_trafic_niveau", -6).coerceIn(-30, -1)
        set(v) { stockage.edit().putInt("pc_trafic_niveau", v.coerceIn(-30, -1)).apply() }

    fun cw(n: Int): String = stockage.getString("pc_trafic_cw_$n", null) ?: TraficSatellite.CW_DEFAUT.getOrElse(n - 1) { "" }
    private fun poseCw(n: Int, t: String) { stockage.edit().putString("pc_trafic_cw_$n", t.take(200)).apply() }

    fun nomVoix(n: Int): String = stockage.getString("pc_trafic_voix_nom_$n", null) ?: "Mémoire $n"
    private fun fichierVoix(n: Int) = File(dossier, "voix-$n.wav")

    /** The other station's callsign, typed in the assistant (kept while SatMe-PC runs). */
    @Volatile var lui = ""

    // ------------------------------------------------------------ the tone the loop arms

    /** SO-50's arming under way: its tone wins. */
    @Volatile private var tonForce: Int? = null

    /** The access tone the Doppler loop should arm (tenths; 0 none; −1 the transmitter's own). */
    fun ctcssVoulu(): Int {
        if (!actif()) return TraficSatellite.AUTO
        tonForce?.let { return it }
        val s = runCatching { situation() }.getOrNull() ?: return TraficSatellite.AUTO
        if (s.geo) return TraficSatellite.AUTO
        return preset(s.catnum).ctcss
    }

    // ------------------------------------------------------------ the guards

    /** Why the rig may not transmit now on the satellite followed, or null (module off: none). */
    fun garde(): String? {
        if (!actif()) return null
        val s = runCatching { situation() }.getOrNull() ?: return null
        if (s.geo || s.catnum == null) return null
        val p = preset(s.catnum)
        TraficSatellite.gardeElevation(s.nom, s.elevationDeg, p.elevationMin)?.let { return it }
        // Above the horizon the loop drives the uplink: it must be the satellite's.
        if ((s.elevationDeg ?: -1.0) >= 0.0) TraficSatellite.gardeMontee(s.transpondeur, s.monteeHz)?.let { return it }
        return null
    }

    // ------------------------------------------------------------ playing an over

    private class Lecture(val quoi: String, val pcm: ShortArray, val simule: Boolean, val debutSonMs: Long, val apres: (() -> Unit)?) {
        var pousses = 0
        var finSonMs = 0L
    }

    @Volatile private var lecture: Lecture? = null
    /** SO-50's arming: waiting for the loop to arm its tone (since when, the armings count then). */
    @Volatile private var armement: Pair<Long, Long>? = null
    @Volatile var raison = ""; private set
    @Volatile var erreur = ""; private set
    @Volatile private var vieMs = 0L
    @Volatile private var derniereAprsMs = 0L
    @Volatile var envois = 0; private set

    val balise = TraficSatellite.Balise()

    /** Something of ours keys the rig now (or is about to). */
    val emet: Boolean get() = lecture != null || armement != null

    /** The page is there (each second while it shows the panel). */
    fun vie() { vieMs = horloge() }

    private fun pageLa(maxMs: Long) = horloge() - vieMs <= maxMs

    /** Why an over cannot start now, or null. */
    fun refus(): String? {
        if (!actif()) return "module « Trafic satellite » désactivé (Réglages › Modules)"
        if (emet) return "une émission du trafic satellite est déjà en cours"
        if (!pageLa(VIE_MS)) return "la page ne répond plus"
        if (simule()) return null
        val p = poste ?: return "pas de poste pour émettre"
        return p.refus() ?: garde()
    }

    /** Keys and plays [pcm] once; null when started, else why not. */
    @Synchronized private fun joue(quoi: String, pcm: ShortArray, apres: (() -> Unit)? = null): String? {
        refus()?.let { erreur = it; return it }
        if (pcm.isEmpty()) return "rien à émettre"
        if (pcm.size > cadence * DUREE_MAX_S) return "trop long : " + DUREE_MAX_S + " s au plus"
        val sim = simule()
        if (!sim) {
            val p = poste ?: return "pas de poste pour émettre"
            val r = runCatching { p.appuie(CLIENT, false, "") }.getOrElse { it.message ?: "erreur" }
            if (r != null) { erreur = "PTT refusé : $r"; return erreur }
        }
        lecture = Lecture(quoi, pcm, sim, horloge() + (if (sim) 0L else DELAI_PTT_MS), apres)
        raison = ""; erreur = ""
        return null
    }

    /** Stops now (the PTT released first), from anyone. */
    @Synchronized fun arrete(pourquoi: String = "arrêt demandé", estErreur: Boolean = false) {
        val l = lecture
        lecture = null
        armement = null
        // SO-50's tone put back whenever the arming is cut, before or during its carrier.
        finArmement()
        if (l != null && !l.simule) runCatching { poste?.relache(pourquoi) }
        if (l != null || estErreur) { raison = pourquoi; if (estErreur) erreur = pourquoi }
    }

    private fun amplitude() = Math.pow(10.0, niveauDb / 20.0)

    /** CW memory [n] (or [texte] when given), its fields filled; null when started. */
    fun envoieCw(n: Int?, texte: String? = null): String? {
        val s = runCatching { situation() }.getOrNull() ?: Situation()
        if (s.catnum != null && !s.geo && TraficSatellite.genre(s.transpondeur) == Genre.FM)
            return "CW par tonalité : seulement par un transpondeur linéaire (BLU), pas par un relais FM"
        val modele = texte ?: n?.let { cw(it) } ?: return "quelle mémoire ?"
        val t = TraficSatellite.texteCw(TraficSatellite.macro(modele, moi(), locator(), lui, s.nom))
        if (t.isBlank()) return "mémoire vide"
        if (t.contains("{")) return "champ inconnu dans la mémoire"
        return joue("CW : $t", pcmCw(t))
    }

    /** [texte] keyed at [wpm] on [tonCw], raised-cosine edges, 100 ms of silence after. */
    fun pcmCw(texte: String): ShortArray {
        val m = ManipulateurCw(cadence, wpm, tonCw.toDouble(), amplitude())
        m.envoie(texte)
        val out = java.io.ByteArrayOutputStream()
        val bloc = ShortArray(960)
        var n = 0
        while (m.occupe && n < cadence * DUREE_MAX_S) {
            m(bloc); n += bloc.size
            for (v in bloc) { out.write(v.toInt() and 0xFF); out.write((v.toInt() shr 8) and 0xFF) }
        }
        val b = out.toByteArray()
        val pcm = ShortArray(b.size / 2 + cadence / 10)
        for (i in 0 until b.size / 2) pcm[i] = ((b[2 * i].toInt() and 0xFF) or (b[2 * i + 1].toInt() shl 8)).toShort()
        return pcm
    }

    /** Voice memory [n] played once; null when started. */
    fun joueVoix(n: Int): String? {
        val f = fichierVoix(n)
        if (!f.isFile) return "mémoire vocale $n vide : l'enregistrer d'abord"
        val (pcm, taux) = runCatching { LectureWav.lis(f.readBytes()) }.getOrNull() ?: return "mémoire vocale $n illisible"
        return joue("voix : " + nomVoix(n), reechantillonne(pcm, taux))
    }

    private fun reechantillonne(pcm: ShortArray, taux: Int): ShortArray {
        if (taux == cadence) return pcm
        val r = fr.f4ioz.satcombo.phonie.Reechantillonneur(taux, cadence)
        val y = r.pousse(pcm, pcm.size)
        return ShortArray(y.size) { (y[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
    }

    /**
     * A recording for voice memory [n] ([wav] from the page, 16-bit PCM,
     * [VOIX_MAX_S] at most): kept, its level brought to the panel's;
     * null when kept, else why not.
     */
    fun enregistreVoix(n: Int, nom: String?, wav: ByteArray): String? {
        if (n !in 1..NB_VOIX) return "mémoire inconnue"
        val (pcm, taux) = LectureWav.lis(wav) ?: return "pas un fichier WAV lisible"
        if (taux !in 8_000..96_000) return "fréquence d'échantillonnage inattendue"
        if (pcm.size > taux.toLong() * VOIX_MAX_S) return "trop long : " + VOIX_MAX_S + " s au plus"
        if (pcm.size < taux / 4) return "trop court"
        // Normalised so its peak sits at the panel's level: a quiet recording still modulates, a loud one never clips.
        var crete = 1
        for (v in pcm) crete = maxOf(crete, kotlin.math.abs(v.toInt()))
        val g = amplitude() * 32767.0 / crete
        val norme = ShortArray(pcm.size) { (pcm[it] * g).toInt().coerceIn(-32767, 32767).toShort() }
        dossier.mkdirs()
        fichierVoix(n).writeBytes(wav(norme, taux))
        nom?.trim()?.take(40)?.takeIf { it.isNotEmpty() }?.let { stockage.edit().putString("pc_trafic_voix_nom_$n", it).apply() }
        return null
    }

    fun effaceVoix(n: Int) { fichierVoix(n).delete() }

    private fun dureeVoix(n: Int): Double? = fichierVoix(n).takeIf { it.isFile }?.let { f ->
        runCatching { LectureWav.lis(f.readBytes()) }.getOrNull()?.let { (p, r) -> Math.round(p.size * 10.0 / r) / 10.0 }
    }

    // ------------------------------------------------------------ SO-50's arming tone

    /** SO-50's arming: the loop arms 74.4 Hz, a 2.5 s carrier, the tone put back; null when started. */
    @Synchronized fun armeSo50(): String? {
        val s = runCatching { situation() }.getOrNull() ?: return "aucun satellite suivi"
        val ton = TraficSatellite.tonArmement(s.catnum, s.nom) ?: return "« " + s.nom + " » n'a pas de tonalité d'armement"
        if ((s.elevationDeg ?: -1.0) < 0.0) return "« " + s.nom + " » sous l'horizon"
        refus()?.let { erreur = it; return it }
        if (simule()) return joue("armement " + s.nom + " (" + TraficSatellite.tonTexte(ton) + ")", ShortArray((cadence * TraficSatellite.DUREE_ARMEMENT_MS / 1000).toInt()))
        tonForce = ton
        armement = horloge() to tonArme().second
        runCatching { rearme() }
        raison = "armement : tonalité " + TraficSatellite.tonTexte(ton) + " demandée au poste"
        return null
    }

    private fun finArmement() {
        if (tonForce == null) return
        tonForce = null
        runCatching { rearme() }
    }

    private fun suitArmement(t: Long) {
        val (depuis, avant) = armement ?: return
        val (ton, n) = tonArme()
        if (n > avant && ton == TraficSatellite.TON_ARMEMENT) {
            armement = null
            val r = joue("armement SO-50 (" + TraficSatellite.tonTexte(ton) + ")", ShortArray((cadence * TraficSatellite.DUREE_ARMEMENT_MS / 1000).toInt())) { finArmement() }
            if (r != null) { finArmement(); erreur = "armement : $r" }
        } else if (t - depuis > 4_000L) {
            armement = null; finArmement()
            erreur = "armement : le poste n'a pas pris la tonalité 74,4 Hz (Doppler en pause ? poste muet ?)"
        }
    }

    // ------------------------------------------------------------ APRS

    /** Why no APRS frame through the satellite followed now (beyond the rig's guards), or null. */
    fun refusAprs(s: Situation): String? {
        if (!TraficSatellite.aprsPossible(s.transpondeur)) return "l'émetteur choisi n'est pas un digipeater APRS sur 145,825 MHz (choisir l'émetteur APRS du satellite)"
        if ((s.elevationDeg ?: -1.0) < 0.0) return "« " + s.nom + " » sous l'horizon"
        val m = s.monteeHz
        if (!simule() && (m == null || m !in AprsEmission.FENETRES[0]))
            return "le poste n'est pas sur 145,825 MHz (connecter le CAT, suivre le satellite)"
        val src = sourceAprs()
        if (src.isBlank() || src.uppercase().startsWith("N0CALL")) return "indicatif de la station manquant (Réglages › Station)"
        return null
    }

    private fun pcmAprs(t: Trame): ShortArray {
        val pcm = Afsk.module(listOf(Ax25.encode(t)), cadence, amplitude(), drapeauxAvant = 40)
        val blanc = cadence * 15 / 100
        return ShortArray(pcm.size + 2 * blanc).also { System.arraycopy(pcm, 0, it, blanc, pcm.size) }
    }

    /** « position », « message » (to [dest]) or « statut », through ARISS; null when started. */
    fun envoieAprs(type: String, dest: String, texte: String): String? {
        val s = runCatching { situation() }.getOrNull() ?: return "aucun satellite suivi"
        refusAprs(s)?.let { return it }
        val t = horloge()
        if (t - derniereAprsMs < TraficSatellite.ECART_MANUEL_MS)
            return "une trame toutes les " + TraficSatellite.ECART_MANUEL_MS / 1000 + " s au plus (le digipeater est partagé)"
        val info = when (type) {
            "message" -> {
                if (dest.isBlank()) return "à qui ? (son indicatif)"
                if (texte.isBlank()) return "le message est vide"
                AprsEmission.message(dest, texte, null)
            }
            "position" -> positionInfo(texte) ?: return "position inconnue (locator dans Réglages)"
            "statut" -> { if (texte.isBlank()) return "le statut est vide"; AprsEmission.statut(texte) }
            else -> return "quoi émettre ?"
        }
        val trame = AprsEmission.trame(sourceAprs(), TraficSatellite.CHEMIN_SATELLITE, info)
        val r = joue("APRS : " + info.take(40), pcmAprs(trame)) { aprsEmis(trame, horloge()) }
        if (r == null) derniereAprsMs = t
        return r
    }

    private fun positionInfo(commentaire: String): String? {
        val (la, lo) = positionAprs() ?: return null
        return AprsEmission.position(la, lo, "/-", commentaire.ifBlank { "SatMe " + locator().take(4) })
    }

    /** The beacon armed for the pass under way (or about to start); null when armed. */
    fun armeBalise(intervalleS: Int?, max: Int?): String? {
        val s = runCatching { situation() }.getOrNull() ?: return "aucun satellite suivi"
        if (!actif()) return "module « Trafic satellite » désactivé (Réglages › Modules)"
        if (!TraficSatellite.aprsPossible(s.transpondeur)) return "l'émetteur choisi n'est pas un digipeater APRS sur 145,825 MHz"
        val aos = s.aosMs; val los = s.losMs
        if (s.catnum == null || aos == null || los == null) return "passage inconnu"
        intervalleS?.let { balise.intervalleMs = it * 1000L }
        max?.let { balise.max = it }
        return balise.arme(s.catnum, aos, los, horloge())
    }

    private fun suitBalise(t: Long) {
        val s = runCatching { situation() }.getOrNull()
        if (balise.expire(t, s?.catnum)) { raison = "balise désarmée : fin du passage"; return }
        if (!balise.armee || s == null || emet) return
        // The page gone quiet: an unattended beacon is not ours to send.
        if (!pageLa(BALISE_VIE_MS)) { balise.desarme(); raison = "balise désarmée : page fermée ou injoignable"; return }
        if (!actif()) { balise.desarme(); return }
        if (!balise.due(t, s.elevationDeg, preset(s.catnum).elevationMin)) return
        if (refusAprs(s) != null || refus() != null) return
        val info = positionInfo("") ?: return
        val trame = AprsEmission.trame(sourceAprs(), TraficSatellite.CHEMIN_SATELLITE, info)
        if (joue("balise APRS " + (balise.envoyees + 1) + "/" + balise.max, pcmAprs(trame)) { aprsEmis(trame, horloge()) } == null) {
            balise.emise(t); derniereAprsMs = t
        }
    }

    // ------------------------------------------------------------ the loop

    /** Often (every 20 ms): the audio paced to the rig, the guards, SO-50's arming, the beacon. */
    @Synchronized fun tic(t: Long = horloge()) {
        if (armement != null) { suitArmement(t); return }
        val l = lecture
        if (l == null) { runCatching { suitBalise(t) }; return }
        if (!l.simule) {
            val p = poste!!
            p.vie(CLIENT)
            if (!p.poste.tenuPar(CLIENT)) { arrete("PTT relâché : " + p.poste.etat.raison.ifBlank { "garde du poste" }, true); return }
        }
        if (!pageLa(VIE_MS)) { arrete("page fermée ou injoignable : émission coupée", true); return }
        if (t >= l.debutSonMs) {
            val voulu = (((t - l.debutSonMs + AVANCE_SON_MS) * cadence) / 1000).toInt().coerceAtMost(l.pcm.size)
            while (l.pousses < voulu) {
                val k = minOf(voulu - l.pousses, BLOC)
                val bloc = l.pcm.copyOfRange(l.pousses, l.pousses + k)
                if (!l.simule) poste!!.sonInterne(CLIENT, bloc, k, cadence)?.let { err -> arrete("son vers le poste : $err", true); return }
                l.pousses += k
            }
        }
        if (l.pousses >= l.pcm.size) {
            if (l.finSonMs == 0L) l.finSonMs = t
            if (t - l.finSonMs >= QUEUE_MS) {
                lecture = null
                if (!l.simule) runCatching { poste?.relache("fin : " + l.quoi) }
                envois++
                raison = "envoyé : " + l.quoi
                runCatching { l.apres?.invoke() }
            }
        }
    }

    fun demarreBoucle() {
        thread(isDaemon = true, name = "satme-trafic", priority = Thread.MAX_PRIORITY) {
            while (true) {
                runCatching { tic() }
                Thread.sleep(20)
            }
        }
    }

    // ------------------------------------------------------------ the page

    fun json(): JSONObject {
        val t = horloge()
        val s = runCatching { situation() }.getOrNull() ?: Situation()
        val p = preset(s.catnum)
        val g = TraficSatellite.genre(s.transpondeur)
        val l = lecture
        val o = JSONObject().put("ok", true).put("module", actif()).put("simule", simule())
            .put("emet", emet).put("raison", raison).put("erreur", erreur).put("envois", envois)
            .put("garde", garde() ?: "").put("refus", if (emet) "" else refus() ?: "")
            .put("moi", moi()).put("locator", locator()).put("lui", lui)
        if (l != null) o.put("enCours", JSONObject().put("quoi", l.quoi).put("progres", Math.round(l.pousses * 1000.0 / l.pcm.size.coerceAtLeast(1)) / 1000.0)
            .put("dureeS", Math.round(l.pcm.size * 10.0 / cadence) / 10.0))
        if (armement != null) o.put("enCours", JSONObject().put("quoi", "armement : attente de la tonalité au poste").put("progres", 0))
        val sat = JSONObject().put("nom", s.nom).put("catnum", s.catnum ?: JSONObject.NULL).put("geo", s.geo)
            .put("el", s.elevationDeg?.let { Math.round(it * 10) / 10.0 } ?: JSONObject.NULL)
            .put("genre", g.name).put("montee", s.monteeHz ?: JSONObject.NULL)
            .put("emetteur", s.transpondeur?.description ?: "")
            .put("aos", s.aosMs ?: JSONObject.NULL).put("los", s.losMs ?: JSONObject.NULL).put("elMax", s.elevationMaxDeg ?: JSONObject.NULL)
            .put("aprs", TraficSatellite.aprsPossible(s.transpondeur))
            .put("armement", TraficSatellite.tonArmement(s.catnum, s.nom) ?: JSONObject.NULL)
        o.put("sat", sat)
        o.put("preset", JSONObject().put("ctcss", p.ctcss).put("el", p.elevationMin).put("p", p.puissancePct))
            .put("ctcssArme", tonArme().first)
            .put("tons", JSONArray(TraficSatellite.TONS))
        o.put("cw", JSONArray((1..NB_CW).map { n -> JSONObject().put("n", n).put("texte", cw(n))
            .put("rendu", TraficSatellite.texteCw(TraficSatellite.macro(cw(n), moi(), locator(), lui, s.nom))) }))
            .put("wpm", wpm).put("ton", tonCw).put("niveau", niveauDb)
        o.put("voix", JSONArray((1..NB_VOIX).map { n -> JSONObject().put("n", n).put("nom", nomVoix(n)).put("dureeS", dureeVoix(n) ?: JSONObject.NULL) }))
        o.put("balise", JSONObject().put("armee", balise.armee).put("envoyees", balise.envoyees).put("max", balise.max)
            .put("intervalleS", balise.intervalleMs / 1000).put("los", if (balise.armee) balise.los else JSONObject.NULL)
            .put("prochaineS", if (balise.armee && balise.derniereMs > 0) ((balise.derniereMs + balise.intervalleMs - t) / 1000).coerceAtLeast(0) else JSONObject.NULL))
        o.put("conseils", JSONArray(TraficSatellite.conseils(g)))
            .put("echange", TraficSatellite.echange(g, moi(), locator(), lui))
        return o
    }

    /**
     * The page's settings (POST, no transmission): the preset of the
     * satellite followed, the CW memories and keyer, the other station's
     * callsign, a voice memory's name or removal, the beacon disarmed.
     */
    fun reglage(q: Map<String, String>): JSONObject {
        val s = runCatching { situation() }.getOrNull() ?: Situation()
        q["lui"]?.let { lui = it.trim().uppercase().filter { c -> c.isLetterOrDigit() || c == '/' || c == '-' }.take(12) }
        s.catnum?.takeIf { !s.geo }?.let { n ->
            var p = preset(n)
            q["ctcss"]?.toIntOrNull()?.let { c -> if (c == TraficSatellite.AUTO || c == TraficSatellite.AUCUN || c in TraficSatellite.TONS) p = p.copy(ctcss = c) }
            q["el"]?.toIntOrNull()?.let { p = p.copy(elevationMin = it.coerceIn(0, 60)) }
            q["p"]?.toIntOrNull()?.let { p = p.copy(puissancePct = it.coerceIn(0, 100)) }
            if (p != preset(n)) {
                val tonChange = p.ctcss != preset(n).ctcss
                posePreset(n, p)
                if (tonChange && actif()) runCatching { rearme() }
            }
            // The preset's power written to the rig (a setting, never a key).
            if (q["puissance"] == "1" && p.puissancePct > 0) poste?.reglePuissance(p.puissancePct)
        }
        for (n in 1..NB_CW) q["cw$n"]?.let { poseCw(n, it) }
        q["wpm"]?.toIntOrNull()?.let { wpm = it }
        q["ton"]?.toIntOrNull()?.let { tonCw = it }
        q["niveau"]?.toIntOrNull()?.let { niveauDb = it }
        q["voixNom"]?.let { v -> q["n"]?.toIntOrNull()?.takeIf { it in 1..NB_VOIX }?.let { n -> stockage.edit().putString("pc_trafic_voix_nom_$n", v.trim().take(40)).apply() } }
        q["voixEfface"]?.toIntOrNull()?.takeIf { it in 1..NB_VOIX }?.let { effaceVoix(it) }
        if (q["balise"] == "0") { balise.desarme(); raison = "balise désarmée" }
        return json()
    }

    /** An over the operator asks (POST through the TX controller); null when started, else why not. */
    fun action(q: Map<String, String>): String? = when {
        q["cw"] != null -> envoieCw(q["cw"]?.toIntOrNull(), q["texte"]?.takeIf { q["cw"] == "texte" })
        q["voix"] != null -> joueVoix(q["voix"]?.toIntOrNull() ?: 0)
        q["aprs"] != null -> envoieAprs(q["aprs"].orEmpty(), q["dest"].orEmpty(), q["texte"].orEmpty())
        q["so50"] == "1" -> armeSo50()
        q["balise"] == "1" -> armeBalise(q["intervalle"]?.toIntOrNull(), q["max"]?.toIntOrNull())
        else -> "quoi émettre ?"
    }

    companion object {
        const val MODULE = "trafic"
        /** The PTT's client for these overs. */
        const val CLIENT = "satme-trafic"
        const val CLE_PRESET = "pc_trafic_preset_"
        const val NB_CW = 6
        const val NB_VOIX = 4
        const val VOIX_MAX_S = 30
        /** No over longer than this (a CW memory at 8 wpm, a voice memory). */
        const val DUREE_MAX_S = 75
        const val DELAI_PTT_MS = 150L
        const val AVANCE_SON_MS = 80L
        const val QUEUE_MS = 200L
        const val BLOC = 960
        /** The page silent longer than this: the over stops. */
        const val VIE_MS = 5_000L
        /** The page silent longer than this: the beacon is disarmed. */
        const val BALISE_VIE_MS = 15_000L

        /** A 16-bit mono WAV of [pcm] at [taux]. */
        fun wav(pcm: ShortArray, taux: Int): ByteArray {
            val b = java.nio.ByteBuffer.allocate(44 + 2 * pcm.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt(36 + 2 * pcm.size).put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(taux).putInt(taux * 2).putShort(2).putShort(16)
            b.put("data".toByteArray()).putInt(2 * pcm.size)
            for (v in pcm) b.putShort(v)
            return b.array()
        }

        /** Where the satellite followed is, from the station. */
        fun situation(station: StationPc): Situation {
            val e = station.etat.value
            val sat = e.suivi ?: return Situation()
            val geo = sat.catalogNumber == 43700 || runCatching { station.qo100.etat.value.auPoste }.getOrDefault(false)
            val p = e.passage
            return Situation(sat.catalogNumber, sat.name, e.position?.elevationDeg, station.emetteurChoisi(),
                station.stationCat.etat.value.monteeHz, p?.aosEpochMs, p?.losEpochMs, p?.maxElevationDeg, geo)
        }
    }
}
