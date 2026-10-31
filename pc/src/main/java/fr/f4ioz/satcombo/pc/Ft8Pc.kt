/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.Ft8Cascade
import fr.f4ioz.satcombo.domain.Ft8Decodeur
import fr.f4ioz.satcombo.domain.Ft8Signal
import fr.f4ioz.satcombo.plateforme.Stockage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * FT8 and FT4 heard through the PC's sound card, as the phone hears them
 * (`Ft8Hub`): slots on UTC (15 s, 7.5 s for FT4), the first one dropped (it
 * starts mid-transmission), each decoded by the engine as it closes, the
 * waterfall and the spectrum. The card's rate brought down by an integer
 * factor to about 12 kHz, the decoder's.
 */
class Ft8Pc(
    private val stockage: Stockage?,
    /** Where [enregistreTranches] keeps the slots (`ft8-tranches/` in the data folder). */
    private val dossierTranches: File?,
    private val horloge: () -> Long = System::currentTimeMillis,
) {

    constructor(horloge: () -> Long = System::currentTimeMillis) : this(null, null, horloge)

    /**
     * Each decoded slot kept as WAV + JSON of its decodes, for the bench: off
     * unless chosen, purged after [GARDE_TRANCHES_MS].
     */
    var enregistreTranches: Boolean
        get() = stockage?.getBoolean("pc_ft8_tranches", false) ?: false
        set(v) { stockage?.edit()?.putBoolean("pc_ft8_tranches", v)?.apply() }

    /**
     * The other mode decoded too (FT4 while listening in FT8, FT8 in FT4): its own
     * slots cut from the same sound, read at the normal depth after the chosen
     * mode's. On by default where settings are kept (the station's card): a pass
     * of RS-44 heard in FT8 on 27 October 2026 held FT4 the listening never read.
     * Receive only.
     */
    var deuxModes: Boolean
        get() = stockage?.getBoolean(CLE_DEUX, true) ?: deuxModesSansReglages
        set(v) { if (stockage != null) stockage.edit().putBoolean(CLE_DEUX, v).apply() else deuxModesSansReglages = v }
    @Volatile private var deuxModesSansReglages = false

    data class Entendu(
        /** Start of its slot, UTC ms. */
        val tranche: Long,
        val texte: String,
        val appelant: String?,
        val appele: String?,
        val locator: String?,
        val frequenceHz: Int,
        val rapportDb: Int,
        val decalageS: Double,
        val mode: String,
        /** Read by expectation during a QSO (see `Ft8Decodeur.Apriori`). */
        val ap: Boolean = false,
    )

    /** My callsign (the settings'), for decoding with what a QSO lets us expect. */
    @Volatile var indicatif = ""

    /**
     * The QSO evident from what was heard: the last station that called me
     * in the last three minutes; reports exchanged once it sent me one.
     */
    fun apriori(): Ft8Decodeur.Apriori? {
        // The contact SatMe transmits in: its partner expected even before it answered.
        qsoEnCours?.let { return it }
        val moi = indicatif.trim().uppercase().ifEmpty { return null }
        val recents = entendus.filter { horloge() - it.tranche < 180_000L && it.appele == moi && it.appelant != null }
        val lui = recents.firstOrNull()?.appelant ?: return null
        val siens = recents.filter { it.appelant == lui }
        return Ft8Decodeur.Apriori(moi, lui, siens.any { Regex("[+-]\\d\\d$").containsMatchIn(it.texte) },
            siens.firstNotNullOfOrNull { it.locator })
    }

    @Volatile var enMarche = false
        private set
    @Volatile var mode = "FT8"
        private set
    @Volatile var entendus: List<Entendu> = emptyList()
        private set
    @Volatile var tranches = 0
        private set
    @Volatile var niveau = 0f
        private set
    @Volatile var cascade: Ft8Cascade? = null
        private set
    /** Each slot's decodes, as they come (PSK Reporter). */
    @Volatile var auDecode: ((List<Entendu>) -> Unit)? = null
    /** When the waterfall's newest row was made: the page draws the slots' edges from it. */
    @Volatile var derniereLigneMs = 0L
        private set
    /** When the card's last block came (or the listening started): no sound when it stops. */
    @Volatile var dernierSonMs = 0L
        private set
    /** Slots decoded in a row with nothing in them, since the start. */
    @Volatile var sansDecodage = 0
        private set

    // Below the sound's threads: a slow decode must never starve the capture (a lost second is a lost slot, and a torn SSTV picture).
    private val decodeur = Executors.newSingleThreadExecutor { r -> Thread(r, "satme-ft8").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    /** Slots handed to the decoder and not done yet: the other mode's skipped while the chosen one's are behind. */
    private val enAttente = java.util.concurrent.atomic.AtomicInteger(0)
    private var facteur = 1
    private var cadence = 12_000

    /** One mode's slots as the sound comes: its buffer, the slot being filled, the first one (cut) dropped. */
    private class Fil(val mode: String) {
        val dureeMs: Long = if (mode == "FT4") 7_500L else 15_000L
        val sig: Ft8Signal.Mode = if (mode == "FT4") Ft8Signal.FT4 else Ft8Signal.FT8
        var tampon = FloatArray(0)
        var ecrit = 0
        var trancheCourante = -1L
        var premiere = true
        fun prepare(cadence: Int) { tampon = FloatArray((dureeMs * cadence / 1000).toInt()); ecrit = 0 }
    }
    /** The chosen mode's, and the other one's when [deuxModes]. */
    private var principal = Fil("FT8")
    private var autre: Fil? = null
    private var somme = 0
    private var dansSomme = 0
    private val bloc = ShortArray(1024)
    private var dansBloc = 0

    /** The followed satellite's Doppler as heard in the audio, (UTC ms, Hz), the last minute. */
    private val dopplers = ArrayDeque<Pair<Long, Double>>()

    /**
     * Each second: the satellite's Doppler on the downlink now, as the CW
     * decoder gets it. When the rig (CAT) or the dongle follows it
     * ([corrigeAvant]) nothing slides in the audio and nothing is removed;
     * [signe] is −1 in LSB, where the audio moves the other way.
     */
    @Synchronized fun ticDoppler(dopplerHz: Double?, corrigeAvant: Boolean, signe: Int, elevationDeg: Double? = null) {
        elevation = elevationDeg
        if (dopplerHz == null || corrigeAvant) { dopplers.clear(); return }
        val t = horloge()
        dopplers.addLast(t to signe * dopplerHz)
        while (dopplers.isNotEmpty() && dopplers.first().first < t - 60_000L) dopplers.removeFirst()
    }

    /** The contact the transmitter works ([Ft8EmissionPc]), for decoding with what it lets us expect. */
    @Volatile var qsoEnCours: Ft8Decodeur.Apriori? = null
    /** FT8/FT4 transmit armed ([Ft8EmissionPc]): a quick decode rather than a deep one. */
    @Volatile var presse = false
    /** The start (UTC ms) of the last slot decoded, -1 before any: the transmitter waits for it. */
    @Volatile var derniereTrancheDecodee = -1L
        private set

    /**
     * Lines added as if decoded (the demonstration's fake stations): newest
     * first, never sent to PSK Reporter.
     */
    @Synchronized fun injecte(l: List<Entendu>) {
        if (l.isEmpty()) return
        entendus = (l + entendus).take(500)
    }

    /** The followed satellite's elevation, null when none is followed or it is down. */
    @Volatile private var elevation: Double? = null
    /** How long the last slot took to decode, ms. */
    @Volatile var dernierDecodageMs = 0L
        private set

    /**
     * The budget for the next slot: deep when the satellite is low (its
     * stations all weak) or when the last slot left plenty of time (a
     * geostationary satellite, a fast PC), normal otherwise. Never more than
     * the slot can afford: a deep decode that took over 40 % of the slot
     * falls back to normal.
     */
    fun profondeur(): Ft8Decodeur.Profondeur {
        // Transmitting: the slot's decodes are needed at once, to answer in the next one.
        if (presse) return Ft8Decodeur.Profondeur.NORMALE
        val budget = dureeTranche() * 0.4
        val bas = elevation?.let { it < 15.0 } ?: false
        return if (dernierDecodageMs < budget || (bas && dernierDecodageMs < dureeTranche() * 0.6))
            Ft8Decodeur.Profondeur.PROFONDE else Ft8Decodeur.Profondeur.NORMALE
    }

    /**
     * The drift over a slot (Hz/s): the Doppler's least-squares slope over the
     * seconds that fell in it, zero without enough of them.
     */
    @Synchronized fun penteSur(debut: Long, fin: Long): Double {
        val l = dopplers.filter { it.first in debut..fin }
        if (l.size < 3) return 0.0
        val mt = l.sumOf { (it.first - debut) / 1000.0 } / l.size
        val md = l.sumOf { it.second } / l.size
        var num = 0.0; var den = 0.0
        for ((t, d) in l) { val x = (t - debut) / 1000.0 - mt; num += x * (d - md); den += x * x }
        return if (den > 0.0) num / den else 0.0
    }

    private fun dureeTranche() = if (mode == "FT4") 7_500L else 15_000L
    private fun signal() = if (mode == "FT4") Ft8Signal.FT4 else Ft8Signal.FT8

    @Synchronized fun demarre(m: String = mode) {
        mode = if (m == "FT4") "FT4" else "FT8"
        principal = Fil(mode); autre = null
        enMarche = true; cadence = 0
        dernierSonMs = horloge(); sansDecodage = 0
        stockage?.edit()?.putBoolean(CLE_MARCHE, true)?.putString(CLE_MODE, mode)?.apply()
    }

    @Synchronized fun arrete() { enMarche = false; stockage?.edit()?.putBoolean(CLE_MARCHE, false)?.apply() }

    /** Listening again as it was left (on when SatMe-PC was closed with it on). */
    fun reprend() {
        val s = stockage ?: return
        if (s.getBoolean(CLE_MARCHE, false)) demarre(s.getString(CLE_MODE, "FT8") ?: "FT8")
    }

    /** Stopped for good (a channel removed): its decoding thread let go. */
    fun ferme() { synchronized(this) { enMarche = false }; decodeur.shutdown() }

    @Synchronized fun vide() { entendus = emptyList(); tranches = 0; sansDecodage = 0 }

    /** A block of the card's sound. */
    @Synchronized fun ecoute(pcm: ShortArray, n: Int, rate: Int) {
        if (!enMarche) return
        dernierSonMs = horloge()
        if (cadence == 0 || cadence != rate / facteur) {
            facteur = Math.round(rate / 12_000.0).toInt().coerceAtLeast(1)
            cadence = rate / facteur
            cascade = Ft8Cascade(BASSE_HZ, HAUTE_HZ, COLONNES, cadence, LIGNES)
            principal.prepare(cadence); autre?.prepare(cadence)
            somme = 0; dansSomme = 0; dansBloc = 0
        }
        // The other mode switched on or off as it is listened to.
        val voulu = if (deuxModes) (if (mode == "FT4") "FT8" else "FT4") else null
        if (autre?.mode != voulu) autre = voulu?.let { Fil(it).also { f -> f.prepare(cadence) } }
        val fils = listOfNotNull(principal, autre)
        var pic = 0
        for (i in 0 until n) {
            val v = pcm[i].toInt()
            if (kotlin.math.abs(v) > pic) pic = kotlin.math.abs(v)
            somme += v; dansSomme++
            if (dansSomme < facteur) continue
            val s = somme / facteur
            somme = 0; dansSomme = 0
            val ech = s / 32768f
            for (f in fils) if (f.ecrit < f.tampon.size) f.tampon[f.ecrit++] = ech
            bloc[dansBloc++] = s.toShort()
            if (dansBloc == bloc.size) { if (cascade?.verse(bloc, dansBloc) == true) derniereLigneMs = horloge(); dansBloc = 0 }
        }
        niveau = pic / 32768f
        // A new slot: the one just over is decoded (the first one, cut, is dropped).
        val t = horloge()
        var principalMis = 0
        for (f in fils) {
            val numero = t / f.dureeMs
            if (numero == f.trancheCourante) continue
            val plein = f.tampon.copyOf(f.ecrit)
            val debut = (numero - 1) * f.dureeMs
            val etaitPremiere = f.premiere || f.trancheCourante < 0
            f.trancheCourante = numero; f.ecrit = 0; f.premiere = false
            val sig = f.sig; val m = f.mode; val c = cadence; val second = f !== principal
            val pente = penteSur(debut, debut + f.dureeMs)
            if (etaitPremiere || plein.size <= sig.parSymbole * sig.symboles / 4) continue
            // A loaded PC: the other mode waits for no one, the chosen mode's slots come first.
            // (Every 15 s both close at once: the chosen one just handed over does not count.)
            if (second && enAttente.get() > principalMis) { autresSautees++; continue }
            enAttente.incrementAndGet()
            if (!second) principalMis = 1
            decodeur.execute {
                try { if (second) decodeAutre(plein, c, sig, m, debut, pente) else decode(plein, c, sig, m, debut, pente) }
                finally { enAttente.decrementAndGet() }
            }
        }
    }

    /**
     * The other mode's slot ([deuxModes]): normal depth, its lines among the others (with their
     * mode), kept and sent on as the chosen mode's; it leaves the chosen mode's counters alone.
     */
    private fun decodeAutre(audio: FloatArray, cadence: Int, sig: Ft8Signal.Mode, m: String, debut: Long, pente: Double) {
        val l = runCatching { Ft8Decodeur.decode(audio, cadence.toDouble(), sig, penteHzS = pente, profondeur = Ft8Decodeur.Profondeur.NORMALE) }
            .getOrDefault(emptyList())
        if (l.isEmpty()) return
        val nouveaux = l.map {
            Entendu(debut, it.message.brut, it.message.appelant, it.message.appele, it.message.locator,
                Math.round(it.frequenceHz).toInt(), it.rapportDb, it.instantS, m, it.ap)
        }
        fr.f4ioz.satcombo.domain.Ft8Indicatifs.commun.sauve()
        if (enregistreTranches) runCatching { garde(audio, cadence, m, debut, l) }
        synchronized(this) {
            entendus = (nouveaux + entendus).take(500)
            dernierAutreMs = horloge()
        }
        auDecode?.let { runCatching { it(nouveaux) } }
    }

    /** The other mode's slots left out because the decoder was behind (a loaded PC). */
    @Volatile var autresSautees = 0
        private set

    /** When the other mode last decoded something (0: never): the reception's diagnostic says so. */
    @Volatile var dernierAutreMs = 0L
        private set

    /** How far into the current slot (0..1), for the page's bar. */
    fun avancement(): Float = (horloge() % dureeTranche()).toFloat() / dureeTranche()

    /** Since the card's last block, for the reception's diagnostic. */
    fun sonDepuisMs(): Long = horloge() - dernierSonMs

    private fun decode(audio: FloatArray, cadence: Int, sig: Ft8Signal.Mode, m: String, debut: Long, pente: Double = 0.0) {
        val t0 = System.nanoTime()
        val l = runCatching { Ft8Decodeur.decode(audio, cadence.toDouble(), sig, penteHzS = pente, profondeur = profondeur(),
            apriori = apriori()) }
            .getOrDefault(emptyList())
        dernierDecodageMs = (System.nanoTime() - t0) / 1_000_000
        fr.f4ioz.satcombo.domain.Ft8Indicatifs.commun.sauve()
        val nouveaux = l.map {
            Entendu(debut, it.message.brut, it.message.appelant, it.message.appele, it.message.locator,
                Math.round(it.frequenceHz).toInt(), it.rapportDb, it.instantS, m, it.ap)
        }
        if (enregistreTranches) runCatching { garde(audio, cadence, m, debut, l) }
        synchronized(this) {
            // Newest first, capped: an evening of listening would otherwise hold thousands of lines.
            entendus = (nouveaux + entendus).take(500)
            tranches++
            derniereTrancheDecodee = debut
            sansDecodage = if (nouveaux.isEmpty()) sansDecodage + 1 else 0
        }
        if (nouveaux.isNotEmpty()) auDecode?.let { runCatching { it(nouveaux) } }
    }

    /** The slot and what came out of it, side by side; older slots purged. */
    private fun garde(audio: FloatArray, cadence: Int, m: String, debut: Long, l: List<Ft8Decodeur.Decode>) {
        val dossier = dossierTranches ?: return
        dossier.mkdirs()
        val nom = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(debut)) + "_" + m
        ecritWav(File(dossier, "$nom.wav"), audio, cadence)
        val json = JSONObject().put("debut", debut).put("mode", m).put("cadence", cadence)
            .put("decodes", JSONArray(l.map {
                JSONObject().put("texte", it.message.brut).put("frequenceHz", it.frequenceHz)
                    .put("rapportDb", it.rapportDb).put("decalageS", it.instantS)
                    .put("penteHzS", it.penteHzS).put("passe", it.passe).put("ap", it.ap)
            }))
        File(dossier, "$nom.json").writeText(json.toString(1))
        val limite = horloge() - GARDE_TRANCHES_MS
        dossier.listFiles()?.filter { it.isFile && it.lastModified() < limite }?.forEach { it.delete() }
    }

    /** 16-bit mono PCM. */
    private fun ecritWav(f: File, audio: FloatArray, cadence: Int) {
        val b = java.nio.ByteBuffer.allocate(44 + audio.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + audio.size * 2).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(cadence).putInt(cadence * 2)
            .putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(audio.size * 2)
        for (v in audio) b.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        f.writeBytes(b.array())
    }

    /** Fed directly (the tests): a slot's audio, decoded at once. */
    fun decodeTranche(audio: FloatArray, cadence: Int, debut: Long, penteHzS: Double = 0.0) =
        decode(audio, cadence, signal(), mode, debut, penteHzS)

    companion object {
        /** Listening on or off, and the mode, kept across restarts. */
        const val CLE_MARCHE = "pc_ft8_marche"
        const val CLE_MODE = "pc_ft8_mode"
        /** The other mode decoded too ([deuxModes]). */
        const val CLE_DEUX = "pc_ft8_deux"
        /** About 5.5 Hz a column (FT8's tones are 6.25 Hz apart). */
        const val COLONNES = 512
        /** About 46 s: three FT8 slots. */
        const val LIGNES = 360
        const val BASSE_HZ = 200
        const val HAUTE_HZ = 3000
        /** Recorded slots are kept three days. */
        const val GARDE_TRANCHES_MS = 3L * 24 * 3600 * 1000
    }
}
