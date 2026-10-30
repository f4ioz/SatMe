/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.demo.ModulesStation

/**
 * [CommandesPc.Monde] on this PC: read from the objects the tabs read, the
 * actions through the same gestures as the page's receive buttons (the SSTV
 * decoder and its sound card, the DATV receiver, the auto-pilot's master
 * switch, the receivers' sound). Nothing here reaches a transmitter, the
 * PTT or the rotor.
 */
class MondeCommandesPc(
    private val station: StationPc,
    private val son: SonStation,
    private val datv: DatvPc,
    private val qo100: Qo100Pc,
    private val carnet: CarnetPc,
    private val autopilote: AutoPiloteSstvPc,
    private val textesDatv: TextesDatvPc?,
    /** The SDR speaker's level (1 normal, 0 muted), through the lowest-wins share ([BaisseSon]). */
    private val baisseSdr: (Float) -> Unit,
    /** Before the DATV receiver starts: the wide band's watch let go (as the page does). */
    private val avantDatv: () -> Unit = {},
) : CommandesPc.Monde {

    override fun satellites(): List<Pair<String, Int>> = station.etat.value.satellites.map { it.name to it.catalogNumber }
    override fun favoris(): Set<Int> = station.etat.value.favoris
    override fun passages(): List<SatPass> = station.etat.value.passages
    override fun passagesDe(catnum: Int, deMs: Long, aMs: Long): List<SatPass> = station.passagesDe(catnum, deMs, aMs)
    override fun elevation(catnum: Int, t: Long): Double? = station.positionSatellite(catnum, t)?.elevationDeg
    override fun elevationMin(): Double = station.settings.minElevDeg.toDouble()

    override fun autopilote(): CommandesPc.Pilote {
        val t = maintenant()
        val j = autopilote.etatSite()
        val ec = j.optJSONObject("enCours")?.let {
            CommandesPc.Pilote.EnCours(it.optString("sat"), it.optString("campagne"), it.optInt("images"), it.optLong("los"))
        }
        val pr = j.optJSONObject("prochain")?.let {
            CommandesPc.Pilote.Prochain(it.optString("sat"), it.optString("campagne"), it.optLong("aos"), it.optDouble("el", 0.0))
        }
        return CommandesPc.Pilote(autopilote.maitre, autopilote.campagnes.count { it.active && it.finMs > t }, ec, pr)
    }

    override fun derniereSstv(): Triple<String, Long, String>? =
        son.images().filter { !SstvPc.horsAntenne(it) }.maxByOrNull { it.timeMs }?.let { Triple(it.satName, it.timeMs, it.mode) }

    override fun datv(): CommandesPc.Datv {
        val e = datv.etat
        val t = maintenant()
        val lu = textesDatv?.let { x -> runCatching { x.confirmes().filter { t - it.dernierMs < 60_000L }.maxByOrNull { it.dernierMs }?.indicatif }.getOrNull() }
            ?: datv.carte.carte?.takeIf { t - datv.carte.carteMs < 60_000L }?.indicatif
        return CommandesPc.Datv(e.actif, e.verrouille, e.merDb, datv.reglage.cielKhz, e.service, lu.orEmpty())
    }

    override fun balise(): CommandesPc.Balise {
        val j = qo100.json()
        val d = qo100.suivi.dernier
        // The wide band beacon (10 491.5 MHz) through the DATV receiver, locked.
        val e = datv.etat
        val large = if (e.actif && e.verrouille && datv.reglage.cielKhz in 10_491_000L..10_492_000L) e.merDb else null
        return CommandesPc.Balise(j.optBoolean("cle") && d != null, d?.rapportDb?.toDouble(), d?.ecartHz?.let { Math.round(it) }, d?.t ?: 0L, large)
    }

    override fun rotor(): CommandesPc.Rotor {
        val r = station.stationRotor.etat.value
        return CommandesPc.Rotor(r.rotorConnected, r.rotorActualAz, r.rotorActualEl, r.rotorAzOnly)
    }

    override fun dernierQso(): Triple<String, String, Long>? = carnet.contacts().firstOrNull()?.let { Triple(it.callsign, it.satName, it.timeMs) }

    override fun sstvActif(): Boolean = son.sstvActif && son.son.etat.value.actif

    @Volatile private var muetPose = false
    /** The DATV sound was on when muted: put back with the rest. */
    @Volatile private var datvSonAvant = false
    override fun muet(): Boolean = muetPose

    // ------------------------------------------------------------------ actions: receive and sound only

    private fun module(id: String, nom: String): CommandesPc.Refus? =
        if (ModulesStation.actif(station.stockage, id)) null
        else CommandesPc.Refus("Le module $nom est désactivé (Réglages › Modules).", "The $nom module is switched off (Settings › Modules).")

    override fun regleAutopilote(on: Boolean): CommandesPc.Refus? {
        module("sstv", "SSTV")?.let { return it }
        autopilote.regleMaitre(on)
        return null
    }

    override fun regleSstv(on: Boolean): CommandesPc.Refus? {
        module("sstv", "SSTV")?.let { return it }
        son.sstvActif = on
        if (on) son.assureSource()?.let { return CommandesPc.Refus("La réception SSTV n'a pas démarré : $it", "SSTV reception didn't start: $it") }
        return null
    }

    override fun regleDatv(on: Boolean): CommandesPc.Refus? {
        module("datv", "DATV")?.let { return it }
        if (!on) { datv.arrete(); datv.voulue = false; return null }
        if (datv.etat.actif) return null
        if (datv.reglage.cielKhz <= 0) return CommandesPc.Refus("Aucune fréquence DATV réglée : la choisir une fois dans l'onglet DATV.",
            "No DATV frequency set: choose one once in the DATV tab.")
        runCatching { avantDatv() }
        datv.demarre(datv.reglage)?.let { return CommandesPc.Refus("La réception DATV n'a pas démarré : $it", "DATV reception didn't start: $it") }
        return null
    }

    @Synchronized override fun regleSon(muet: Boolean): CommandesPc.Refus? {
        if (muet == muetPose) return null
        if (muet) {
            baisseSdr(0f)
            datvSonAvant = datv.reglage.son
            if (datvSonAvant) runCatching { datv.coupeSon(false) }
        } else {
            baisseSdr(1f)
            if (datvSonAvant) runCatching { datv.coupeSon(true) }
            datvSonAvant = false
        }
        muetPose = muet
        return null
    }
}
