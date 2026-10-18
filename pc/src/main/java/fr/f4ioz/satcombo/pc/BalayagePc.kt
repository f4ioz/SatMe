/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.demo.ReponseWeb
import fr.f4ioz.satcombo.domain.MemoiresSdr
import fr.f4ioz.satcombo.domain.PileBandes
import fr.f4ioz.satcombo.plateforme.Stockage
import fr.f4ioz.satcombo.sdr.Balayage
import fr.f4ioz.satcombo.sdr.Dsp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * The SDR tab's scan and band stack, run here (the page may close, the scan
 * goes on until « Arrêter »). Receive only: it moves the receiver's
 * listening offset or its PLL ([SdrPc]), never a transmitter; it does not
 * start while another tab holds the receiver (DATV, ADS-B, QO-100, a
 * radiosonde, METEOR), and stops when one takes it or the receiver stops.
 * The test generator and a recording do: they serve to try it.
 */
class BalayagePc(private val sdr: SdrPc, private val memoires: MemoiresSdrPc?, private val stockage: Stockage, dossier: File,
                 private val horloge: () -> Long = System::currentTimeMillis) {

    @Volatile var machine: Balayage? = null; private set
    @Volatile var actif = false; private set
    @Volatile var message = ""; private set
    @Volatile private var generation = 0
    /** What the scan was started on (the folder's name, the range), for the page. */
    @Volatile private var quoi = ""

    // ---------------------------------------------------------------- the band stack

    private val fichierPile = File(dossier, "pile-bandes.json")
    private val pile: PileBandes.Registre = runCatching { PileBandes.lit(fichierPile.readText()) }.getOrDefault(PileBandes.Registre())

    private fun ecouteHz(): Long = sdr.etat.value.let { if (it.cielHz > 0) it.cielHz + it.decalageHz else 0L }

    /** Every second: the frequency listened to watched, noted on its band's stack after 5 s (not while scanning). */
    fun ticPile() {
        val e = sdr.etat.value
        val libre = e.actif && !actif && !sdr.pourSonde && !sdr.pourMeteor
        val change = pile.observe(horloge(), if (libre) PileBandes.Entree(ecouteHz(), sdr.modeChoisi, sdr.station.settings.sdrBandwidthHz) else null)
        if (change) runCatching {
            fichierPile.parentFile?.mkdirs()
            val tmp = File(fichierPile.parentFile, fichierPile.name + ".tmp")
            tmp.writeText(pile.json().toString(1))
            if (!tmp.renameTo(fichierPile)) { fichierPile.delete(); tmp.renameTo(fichierPile) }
        }
    }

    /** A band's button: the next entry of its stack tuned (the scan stopped first). */
    fun rappel(cle: String): JSONObject {
        if (cle !in PileBandes.CLES) return JSONObject().put("ok", false).put("erreur", "bande inconnue")
        if (sdr.surQo100) return JSONObject().put("ok", false).put("erreur", "le récepteur est à l'onglet QO-100 : l'y rendre d'abord")
        val e = pile.rappel(cle, ecouteHz()) ?: return JSONObject().put("ok", false).put("erreur", "rien encore sur cette bande")
        if (e.hz !in sdr.gammeHz) return JSONObject().put("ok", false).put("erreur", "hors de la gamme du récepteur (" + (e.hz / 1e6) + " MHz)")
        arrete("bande choisie")
        sdr.suitSatellite = false
        if (e.mode.isNotEmpty() && e.mode != sdr.modeChoisi) sdr.modeChoisi = e.mode
        sdr.station.settings.sdrBandwidthHz = e.largeurHz.coerceIn(0, 24_000)
        sdr.manuelleHz = e.hz
        sdr.decalageHz = 0
        return pileJson().put("ok", true).put("hz", e.hz)
    }

    fun pileJson(): JSONObject = pile.json(ecouteHz()).put("ok", true)

    // ---------------------------------------------------------------- the lockouts of ranges, kept

    private fun passesGardees(): List<Long> = (stockage.getString(CLE_PASSE, "") ?: "").split(',').mapNotNull { it.trim().toLongOrNull() }

    private fun gardePasses(l: List<Long>) { stockage.edit().putString(CLE_PASSE, l.distinct().takeLast(500).joinToString(",")).apply() }

    // ---------------------------------------------------------------- the scan

    /** Why the scan may not run on the receiver now, or null. */
    private fun empeche(): String? {
        val e = sdr.etat.value
        return when {
            sdr.datvTient != null && sdr.datvTient == sdr.recepteur -> "le récepteur reçoit la DATV (onglet DATV)"
            sdr.adsbTient() != null && sdr.adsbTient() == sdr.recepteur -> "le récepteur reçoit l'ADS-B (onglet Avions)"
            !e.actif -> "le récepteur ne reçoit pas : « Démarrer » d'abord (onglet SDR)"
            sdr.surQo100 -> "le récepteur est à l'onglet QO-100"
            sdr.pourSonde -> "le récepteur écoute une radiosonde"
            sdr.pourMeteor -> "le récepteur reçoit METEOR"
            else -> null
        }
    }

    private fun refus(t: String) = JSONObject().put("ok", false).put("erreur", t)

    private fun mhz(s: String): Long? = s.trim().replace(',', '.').replace(" ", "").toDoubleOrNull()?.let { Math.round(it * 1e6) }

    /** Started from the page's fields ([p]): the way, what to go through, when to stop and go on. */
    fun demarre(p: (String) -> String): JSONObject {
        empeche()?.let { return refus(it) }
        val genre = runCatching { Balayage.Genre.valueOf(p("genre").uppercase()) }.getOrDefault(Balayage.Genre.PLAGE)
        val critere = runCatching { Balayage.Critere.valueOf(p("critere").uppercase()) }.getOrDefault(Balayage.Critere.NIVEAU)
        if (critere == Balayage.Critere.SQUELCH && genre != Balayage.Genre.SPECTRE && sdr.station.settings.sdrSquelchDb <= -120)
            return refus("le silencieux est ouvert : le régler d'abord (onglet SDR, Silencieux), ou s'arrêter sur le niveau")
        val l = memoires?.memoires().orEmpty()
        fun canal(m: MemoiresSdr.Memoire) = Balayage.Canal(m.hz, m.mode, m.largeurHz, m.nom, m.id)
        var canaux = emptyList<Balayage.Canal>()
        var debut = 0L; var fin = 0L
        val pas = p("pas").replace(',', '.').toDoubleOrNull()?.let { v -> Balayage.PAS.minByOrNull { Math.abs(it - v) } } ?: 12_500.0
        val gamme = sdr.gammeHz
        if (genre == Balayage.Genre.MEMOIRES) {
            val d = p("dossier"); val et = p("etiquette")
            canaux = l.filter { !it.passe && it.hz in gamme && (if (et.isNotEmpty()) it.etiquette == et else MemoiresSdr.sous(it.dossier, MemoiresSdr.dossier(d))) }
                .sortedBy { it.hz }.map(::canal)
            if (canaux.isEmpty()) return refus("aucune mémoire à balayer dans ce dossier (ou toutes passées)")
            quoi = if (et.isNotEmpty()) "étiquette « $et »" else if (d.isBlank()) "toutes les mémoires" else "« " + MemoiresSdr.dossier(d) + " »"
        } else {
            debut = mhz(p("debut")) ?: return refus("le début de la plage, en MHz")
            fin = mhz(p("fin")) ?: return refus("la fin de la plage, en MHz")
            if (fin < debut) { val t = debut; debut = fin; fin = t }
            if (debut !in gamme || fin !in gamme) return refus("plage hors de la gamme du récepteur")
            if ((fin - debut) / pas > Balayage.MAX_CANAUX) return refus("plage trop large pour ce pas (" + Balayage.MAX_CANAUX + " canaux au plus)")
            quoi = String.format(java.util.Locale.FRANCE, "%.4f – %.4f MHz", debut / 1e6, fin / 1e6)
        }
        val prioText = p("priorite").trim()
        val prio = when {
            prioText.isEmpty() -> null
            else -> l.firstOrNull { it.id == prioText }?.let(::canal) ?: mhz(prioText)?.takeIf { it in gamme }?.let { Balayage.Canal(it, p("mode"), 0, "priorité") }
        }
        val mode = p("mode").uppercase().takeIf { it in setOf("NFM", "USB", "LSB", "AM", "CW") }.orEmpty()
        val r = Balayage.Reglages(
            genre = genre, canaux = canaux, debutHz = debut, finHz = fin, pasHz = pas,
            mode = mode, largeurHz = p("bw").toIntOrNull()?.coerceIn(0, 24_000) ?: 0,
            critere = critere, seuilDb = p("seuil").replace(',', '.').toDoubleOrNull()?.coerceIn(3.0, 60.0) ?: 12.0,
            reprise = runCatching { Balayage.Reprise.valueOf(p("reprise").uppercase()) }.getOrDefault(Balayage.Reprise.PORTEUSE),
            maintienMs = ((p("maintien").replace(',', '.').toDoubleOrNull() ?: 2.0).coerceIn(0.0, 60.0) * 1000).toLong(),
            tempsMs = ((p("temps").replace(',', '.').toDoubleOrNull() ?: 5.0).coerceIn(1.0, 600.0) * 1000).toLong(),
            attenteMs = if (genre == Balayage.Genre.SPECTRE) 150L else 80L,
            passe = passesGardees(), priorite = prio,
            prioriteMs = ((p("prioriteS").replace(',', '.').toDoubleOrNull() ?: 5.0).coerceIn(1.0, 600.0) * 1000).toLong(),
            // The panorama's useful part: 85 % of the receiver's band.
            largeurVueHz = (Dsp.RTL_RATE * 0.85).toLong(),
            boucle = p("boucle") != "0")
        arrete("")
        val b = Balayage(r, horloge())
        // Not following a satellite any more: the scan sets the frequency.
        sdr.suitSatellite = false
        machine = b; actif = true; message = ""
        val gen = ++generation
        thread(isDaemon = true, name = "satme-balayage") { boucle(b, gen) }
        return json().put("ok", true)
    }

    /** Stopped ([pourquoi] said on the page); the hits stay shown. */
    fun arrete(pourquoi: String) {
        if (!actif) return
        generation++
        actif = false
        message = pourquoi
    }

    private fun vue(spectre: Boolean): Balayage.Vue {
        if (spectre) sdr.demandePanorama()
        val db = if (spectre) sdr.panorama else sdr.spectre
        val pll = if (spectre) sdr.panoramaPllHz else sdr.spectrePllHz
        val trame = if (spectre) sdr.panoramaTrames else sdr.trames
        val bin = if (db == null) 1.0 else if (spectre) Dsp.RTL_RATE.toDouble() / db.size else Dsp.RTL_RATE.toDouble() / Dsp.DECIM_1 / db.size
        val centre = sdr.cielDePll(pll)
        return Balayage.Vue(trame, centre, bin, db, sdr.sensSpectre(centre), sdr.squelchOuvert)
    }

    private fun applique(o: Balayage.Ordre, v: Balayage.Vue) {
        when (o) {
            is Balayage.Ordre.Accorde -> {
                val c = o.canal
                val m = MemoiresSdr.modeSdr(c.mode)
                if (m.isNotEmpty() && m != sdr.modeChoisi) sdr.modeChoisi = m
                if ((m.isNotEmpty() || c.largeurHz > 0) && sdr.station.settings.sdrBandwidthHz != c.largeurHz) sdr.station.settings.sdrBandwidthHz = c.largeurHz
                val ciel = if (sdr.fictif) sdr.etat.value.cielHz else sdr.manuelleHz
                val depuisPll = Math.abs(c.hz - v.centreHz)
                // Within the band captured: the offset only (no PLL move, no click); else the PLL
                // moved, the channel 40 kHz above it (never on its DC line). A recording, the generator: offset only.
                if (sdr.fictif || (v.db != null && depuisPll in 3_000L..75_000L && Math.abs(c.hz - ciel) <= 80_000L)) {
                    sdr.decalageHz = (c.hz - ciel).coerceIn(-80_000L, 80_000L).toInt()
                } else {
                    sdr.manuelleHz = c.hz - 40_000L
                    sdr.decalageHz = 40_000
                }
            }
            is Balayage.Ordre.Centre -> if (!sdr.fictif) { sdr.manuelleHz = o.hz; sdr.decalageHz = 0 }
        }
    }

    private fun boucle(b: Balayage, gen: Int) {
        val spectre = b.reglages.genre == Balayage.Genre.SPECTRE
        try {
            while (generation == gen) {
                empeche()?.let { arrete(it); return }
                val v = vue(spectre)
                val o = b.tic(horloge(), v)
                if (o != null) applique(o, v)
                if (b.etat == Balayage.Etat.FINI) { arrete(if (b.taille == 0) "rien à balayer" else "fin du tour"); return }
                Thread.sleep(25)
            }
        } catch (e: Exception) {
            arrete("erreur : " + (e.message ?: e.javaClass.simpleName))
        }
    }

    fun json(): JSONObject {
        val b = machine
        val o = (b?.json(horloge()) ?: JSONObject()).put("ok", true).put("actif", actif).put("message", message).put("quoi", quoi)
            .put("passeGardees", JSONArray(passesGardees()))
            .put("pasOfferts", JSONArray(Balayage.PAS))
        empeche()?.let { o.put("empeche", it) }
        return o
    }

    /** `/api/balayage…` and `/api/pile-bandes…`. */
    fun sert(chemin: String, methode: String, p: (String) -> String): ReponseWeb {
        val o = runCatching {
            if (chemin == "/api/pile-bandes") return@runCatching pileJson()
            if (chemin == "/api/pile-bandes/rappel") return@runCatching if (methode == "POST") rappel(p("bande")) else refus("POST")
            if (methode != "POST") return@runCatching json()
            when (chemin) {
                "/api/balayage/demarrer" -> demarre(p)
                "/api/balayage/arreter" -> { arrete("arrêté"); json() }
                "/api/balayage/reprendre" -> { machine?.reprend(); json() }
                // Passed by from now on: the frequency (a memory's flag when it is one), or put back.
                "/api/balayage/passe" -> {
                    val hz = p("hz").toLongOrNull() ?: machine?.let { m -> m.json(horloge()).optJSONObject("arret")?.optLong("hz") } ?: return@runCatching refus("quelle fréquence ?")
                    val on = p("on") != "0"
                    val id = p("id").ifEmpty { machine?.touches()?.firstOrNull { it.canal.hz == hz }?.canal?.id.orEmpty() }
                    if (id.isNotEmpty()) memoires?.passe(id, on)
                    else gardePasses(if (on) passesGardees() + hz else passesGardees().filter { Math.abs(it - hz) > 500 })
                    if (on) machine?.ajoutePasse(hz) else machine?.retirePasse(hz)
                    json()
                }
                // A hit kept as a memory, in the folder « Balayage » (or the one given).
                "/api/balayage/garde" -> {
                    val hz = p("hz").toLongOrNull() ?: return@runCatching refus("quelle fréquence ?")
                    val t = machine?.touches()?.firstOrNull { it.canal.hz == hz }
                    val m = memoires ?: return@runCatching refus("pas de mémoires ici")
                    if (t?.canal?.id?.isNotEmpty() == true) return@runCatching refus("déjà en mémoire : « " + t.canal.nom + " »")
                    val c = t?.canal ?: Balayage.Canal(hz)
                    val nom = p("nom").ifBlank { String.format(java.util.Locale.FRANCE, "%.4f", hz / 1e6) }
                    m.pose(MemoiresSdr.Memoire("", nom, hz, c.mode, c.largeurHz, dossier = p("dossier").ifBlank { "Balayage" }))
                    json().put("garde", nom)
                }
                else -> refus("route inconnue")
            }
        }.getOrElse { refus(it.message ?: "erreur") }
        return ReponseWeb("200 OK", "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val CLE_PASSE = "pc_balayage_passe"
    }
}
