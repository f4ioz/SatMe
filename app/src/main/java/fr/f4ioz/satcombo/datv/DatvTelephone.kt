/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * DATV on the phone, through a SatMe-Node: the MiniTiouner stays next to the
 * antenna, on a Raspberry Pi, and the phone drives it over the network —
 * no USB driver here, and the phone's hardware decoder shows the picture.
 * The node's protocol is the engine's ([ClientNoeud]), the same as SatMe-PC's.
 */
class DatvTelephone(context: Context) {

    data class Ui(
        val associe: Boolean = false,
        val adresse: String = "",
        val nom: String = "",
        val reglage: ReglageDatv = ReglageDatv(),
        val etat: EtatDatv = EtatDatv(),
        val points: List<Pair<Int, Int>> = emptyList(),
        /** The stream's address while receiving (the player reads it), else "". */
        val flux: String = "",
        val message: String = "",
        val occupe: Boolean = false,
    )

    private val stockage = fr.f4ioz.satcombo.data.stockage(context, "satcombo_datv")
    private val client = ClientNoeud(stockage)
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var suivi: Job? = null

    private val _ui = MutableStateFlow(Ui(associe = client.associe, adresse = client.adresse, nom = client.nom, reglage = lisReglage()))
    val ui = _ui.asStateFlow()

    /** The wide band's spectrum (the ground station's), off unless chosen; read only while the screen shows. */
    val large = SpectreLargeTelephone(portee)
    private val _largeOn = MutableStateFlow(stockage.getBoolean("datv_large", false))
    val largeOn = _largeOn.asStateFlow()
    @Volatile private var ecranVu = false
    /** The names heard on the wide band (frequency + rate → name), kept 24 h. */
    val noms = NomsSignaux().also { it.reprends(stockage.getString("datv_large_noms", null), System.currentTimeMillis()) }

    fun largeOn(on: Boolean) {
        stockage.edit().putBoolean("datv_large", on).apply()
        _largeOn.value = on
        if (on && ecranVu) large.ouvre() else large.ferme()
    }

    /** A signal of the wide band received: its frequency and rate, the rest (oscillator, input, LNB supply) as it is. */
    fun choisis(sg: SignalLarge) {
        regle(_ui.value.reglage.copy(cielKhz = sg.centreKhz, srKs = sg.srKs))
        recois()
    }

    /** The signal the node is on, when it receives. */
    fun tenu(sg: SignalLarge): Boolean {
        val u = _ui.value
        return u.etat.actif && Math.abs(sg.centreKhz - u.reglage.cielKhz) <= maxOf(NomsSignaux.tolerance(sg.srKs), sg.largeurKhz / 2)
    }

    /** The name received now (the service's, else its provider's) noted for this frequency and rate. */
    private fun noteNom(e: EtatDatv) {
        val r = _ui.value.reglage
        val n = e.service.ifBlank { e.fournisseur }.trim()
        if (!e.verrouille || n.isEmpty()) return
        var nouveau = false
        listOf(r.srKs, e.srTrouveKs).filter { it > 0 }.map { BandeLarge.debit(it.toDouble()) }.distinct()
            .forEach { if (noms.note(r.cielKhz, it, n, System.currentTimeMillis())) nouveau = true }
        if (nouveau) stockage.edit().putString("datv_large_noms", noms.texte()).apply()
    }

    private fun lisReglage() = ReglageDatv(
        cielKhz = stockage.getLong("ciel_khz", Datv.BALISE_KHZ), srKs = stockage.getInt("sr", 1500),
        olKhz = stockage.getLong("ol_khz", 9_750_000L), entreeB = stockage.getBoolean("entree_b", false),
        // The LNB supply is never kept: asked for each time.
        lnb = "")

    private fun garde(r: ReglageDatv) {
        stockage.edit().putLong("ciel_khz", r.cielKhz).putInt("sr", r.srKs).putLong("ol_khz", r.olKhz).putBoolean("entree_b", r.entreeB).apply()
    }

    fun regle(r: ReglageDatv) { _ui.update { it.copy(reglage = r) } }

    /** Joins the node at [adresse] with its code; an empty address forgets it. */
    fun rejoins(adresse: String, code: String) {
        _ui.update { it.copy(occupe = true, message = "") }
        portee.launch {
            arreteIci()
            val e = client.rejoins(adresse, code)
            _ui.update { it.copy(occupe = false, associe = client.associe, adresse = client.adresse, nom = client.nom, message = e ?: "") }
        }
    }

    /** Starts receiving with the settings on screen: the node starts LongMynd, the phone follows its status and plays its stream. */
    fun recois() {
        val r = _ui.value.reglage
        r.refus()?.let { m -> _ui.update { it.copy(message = m) }; return }
        _ui.update { it.copy(occupe = true, message = "") }
        portee.launch {
            garde(r)
            val e = client.demarre(r)
            if (e != null) { _ui.update { it.copy(occupe = false, message = e) }; return@launch }
            _ui.update { it.copy(occupe = false, etat = EtatDatv(actif = true), points = emptyList(), flux = client.urlFlux()) }
            suis()
        }
    }

    /** The node's status twice a second while receiving; it says when LongMynd stopped there, and why. */
    private fun suis() {
        suivi?.cancel()
        suivi = portee.launch {
            var echecs = 0
            while (isActive) {
                runCatching { client.etat() }.onSuccess { j ->
                    echecs = 0
                    val e = Datv.etatDe(j, _ui.value.etat).copy(actif = j.optBoolean("actif"), erreur = j.optString("erreur"))
                    noteNom(e)
                    _ui.update { it.copy(etat = e, points = Datv.points(j), flux = if (e.actif) it.flux else "",
                        message = if (!e.actif && e.erreur.isNotBlank()) e.erreur else it.message) }
                    if (!e.actif) return@launch
                }.onFailure { if (++echecs >= 10) _ui.update { u -> u.copy(message = "node silencieux : " + (it.message ?: "")) } }
                delay(500)
            }
        }
    }

    fun arrete() {
        portee.launch { arreteIci(); client.arrete() }
    }

    private fun arreteIci() {
        suivi?.cancel(); suivi = null
        _ui.update { it.copy(etat = it.etat.copy(actif = false), flux = "") }
    }

    /** The beacon locked: the LNB's oscillator corrected by the offset the node measured, and received again. */
    fun cale() {
        val u = _ui.value
        if (!u.etat.verrouille || u.etat.fiTrouveeKhz <= 0) { _ui.update { it.copy(message = "accrocher d'abord un signal (la balise)") }; return }
        val ecart = u.etat.fiTrouveeKhz - u.reglage.fiKhz
        regle(u.reglage.copy(olKhz = u.reglage.olKhz - ecart))
        recois()
    }

    /** The screen left: the phone stops following (the node keeps receiving until asked to stop). */
    fun quitte() { suivi?.cancel(); ecranVu = false; large.ferme() }

    /** Back on the screen: follow again if the node is receiving. */
    fun revient() {
        ecranVu = true
        if (_largeOn.value) large.ouvre()
        if (!client.associe) return
        portee.launch {
            runCatching { client.etat() }.onSuccess { j ->
                // The node's own oscillator, input and LNB supply as last used (calibrated on the beacon from the PC, say):
                // this phone's stored ones may be old, and « Recevoir » must not cut the LNB the node feeds.
                _ui.update { u -> Datv.reglageDe(j, u.reglage).let { n -> u.copy(reglage = u.reglage.copy(olKhz = n.olKhz, entreeB = n.entreeB, lnb = n.lnb)) } }
                if (j.optBoolean("actif")) {
                    // What it receives with, LNB supply included: tuning from here keeps it.
                    _ui.update { it.copy(etat = Datv.etatDe(j, it.etat).copy(actif = true), flux = client.urlFlux(), reglage = Datv.reglageDe(j, it.reglage)) }
                    suis()
                }
            }
        }
    }
}
