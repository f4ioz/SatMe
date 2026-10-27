/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat

/** How the satellite's frequencies lie, which decides how the rig is set up. */
enum class DispositionSat {
    /** Up and down on two bands (V/U, U/V): satellite mode, MAIN downlink, SUB uplink. */
    CROSS_BAND,
    /** Up and down on one band (the ISS V/V): split, RX on VFO A, TX on VFO B. */
    SAME_BAND,
    /** Receive only. */
    BEACON
}

/**
 * A leg's mode, as each kind of rig wants it: a CI-V byte, or a name ("USB",
 * "FM"…); [donnees]: its data variant (USB-D…) while FT8/FT4 wants it, on
 * the rigs SatMe switches it on (see [PosteCat.donneesPossibles]).
 */
data class ModePoste(val civ: Int, val nom: String, val donnees: Boolean = false)

/**
 * The data flags (USB-D…) of the bands FT8/FT4 uses, as read before SatMe
 * switched them: in satellite mode both bands ([secondaire] is SUB), else the
 * VFO in use alone. What is put back afterwards.
 */
data class EtatDonnees(val satellite: Boolean, val principale: Boolean, val secondaire: Boolean? = null)

/**
 * The rig as the Doppler loop sees it: what to write, what to read. All
 * frequencies here are the **rig's** (converters already applied).
 *
 * Two kinds: one Icom on CI-V, which juggles MAIN/SUB or VFO A/B on one bus;
 * and a pair ([paire]) — two rigs, or a TS-2000 / TH-D72 driven as two —
 * where each leg has its own rig and nothing has to be juggled.
 */
interface PosteCat {
    val paire: Boolean

    /** Sets the rig up for a satellite: layout, modes of each leg, access tone (0: none). */
    suspend fun arme(disposition: DispositionSat, descente: ModePoste, montee: ModePoste, ctcssDixiemes: Int)

    /** What the rig knew of its bands is void (a new satellite). */
    fun oublieBandes() {}

    /** The downlink as the rig shows it, or null. */
    suspend fun lisDescente(memeBande: Boolean): Long?

    /** The uplink as the rig shows it — a pair only (its TX rig has its own knob). */
    suspend fun lisMontee(): Long? = null

    /** The downlink alone (a beacon, an uplink the rig cannot reach). */
    suspend fun ecrisDescente(hz: Long)

    suspend fun ecrisCouple(descente: Long, montee: Long, memeBande: Boolean)

    suspend fun ecrisMontee(montee: Long, memeBande: Boolean)

    /** The rig's mode as a CI-V byte, null when not known (a pair). */
    suspend fun lisMode(): Int? = null

    /** Writes the rig's mode (the band in use), its data variant too when [donnees]. */
    suspend fun ecrisMode(civ: Int, donnees: Boolean = false) {}

    /**
     * Does SatMe switch this rig's data mode (USB-D…)? The IC-9700 only;
     * a pair (FT-817, IC-705, TS-2000, TH-D72) is left as it is.
     */
    val donneesPossibles: Boolean get() = false

    /** The data flag of the band in use, null when unknown or not switched by SatMe. */
    suspend fun lisDonnees(): Boolean? = null

    /**
     * The data flags of the bands FT8/FT4 uses (both in satellite mode), read
     * then set to [on], the modes untouched; returns what was read, null when
     * the rig did not say.
     */
    suspend fun posDonnees(on: Boolean): EtatDonnees? = null

    /** Puts back the data flags [posDonnees] read. */
    suspend fun remetDonnees(e: EtatDonnees) {}
}

/** An Icom satellite rig on CI-V (IC-9700, IC-9100, IC-910H). */
class PosteCatCiv(private val cat: CivController) : PosteCat {
    override val paire = false

    override suspend fun arme(disposition: DispositionSat, descente: ModePoste, montee: ModePoste, ctcssDixiemes: Int) {
        when (disposition) {
            DispositionSat.CROSS_BAND -> {
                cat.setSatelliteMode(true)
                cat.setSplitOn(false)
                // SUB = uplink: mode + CTCSS while selected.
                cat.selectMainSub(true)
                cat.setModeData(montee.civ, montee.donnees)
                if (ctcssDixiemes > 0) {
                    cat.setToneFreq(ctcssDixiemes); cat.setToneOn(true)
                } else cat.setToneOn(false)
                // MAIN = downlink: mode; finish on MAIN.
                cat.selectMainSub(false)
                cat.setModeData(descente.civ, descente.donnees)
            }
            DispositionSat.SAME_BAND -> {
                // ISS V/V etc.: sat mode OFF, split ON, RX=VFO A, TX=VFO B.
                cat.armSameBandSplit()
                cat.selectVfo(true)
                cat.setModeData(montee.civ, montee.donnees)
                if (ctcssDixiemes > 0) {
                    cat.setToneFreq(ctcssDixiemes); cat.setToneOn(true)
                } else cat.setToneOn(false)
                cat.selectVfo(false)
                cat.setModeData(descente.civ, descente.donnees)
            }
            DispositionSat.BEACON -> {
                // Receive-only: sat mode off, tune downlink on MAIN only.
                cat.setSatelliteMode(false)
                cat.setSplitOn(false)
                cat.setToneOn(false)
                cat.selectMainSub(false)
                cat.setModeData(descente.civ, descente.donnees)
            }
        }
    }

    override fun oublieBandes() = cat.forgetBands()

    override suspend fun lisDescente(memeBande: Boolean): Long? =
        if (memeBande) cat.readVfoAFrequency() else cat.readMainFrequency()

    override suspend fun ecrisDescente(hz: Long) { cat.readMainFrequency(); cat.setFrequency(hz) }

    override suspend fun ecrisCouple(descente: Long, montee: Long, memeBande: Boolean) {
        if (memeBande) cat.setSplitPair(descente, montee) else cat.setSatellitePair(descente, montee)
    }

    override suspend fun ecrisMontee(montee: Long, memeBande: Boolean) {
        if (memeBande) cat.setSplitUplink(montee) else cat.setUplink(montee)
    }

    override suspend fun lisMode(): Int? = cat.readMode()

    override suspend fun ecrisMode(civ: Int, donnees: Boolean) { cat.setModeData(civ, donnees) }

    override val donneesPossibles: Boolean get() = cat.modele.donnees

    override suspend fun lisDonnees(): Boolean? = cat.readDataMode()

    /**
     * In satellite mode both bands, SUB then MAIN, ending on MAIN (the
     * operator's dial): 07 D1, 1A 06 (read), 1A 06 01 01, 07 D0, 1A 06,
     * 1A 06 01 01. Out of it, the VFO in use: 1A 06, 1A 06 01 01. The
     * 0x26 shortcut is refused in satellite mode (see [CivController.setDataMode]).
     */
    override suspend fun posDonnees(on: Boolean): EtatDonnees? {
        if (!cat.modele.donnees) return null
        val sat = cat.readSatelliteMode() ?: return null
        if (!sat) {
            val avant = cat.readDataMode() ?: return null
            cat.setDataMode(on)
            return EtatDonnees(false, avant)
        }
        cat.selectMainSub(true)
        val sub = cat.readDataMode()
        cat.setDataMode(on)
        cat.selectMainSub(false)
        val main = cat.readDataMode()
        cat.setDataMode(on)
        if (main == null && sub == null) return null
        return EtatDonnees(true, main ?: false, sub ?: false)
    }

    override suspend fun remetDonnees(e: EtatDonnees) {
        if (!cat.modele.donnees) return
        val sat = cat.readSatelliteMode() ?: e.satellite
        if (sat) {
            cat.selectMainSub(true)
            cat.setDataMode(e.secondaire ?: e.principale)
            cat.selectMainSub(false)
        }
        cat.setDataMode(e.principale)
    }
}
