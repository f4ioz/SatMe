/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The ADALM-PLUTO's transmit side: **the only code that writes a TX
 * attribute or a TX buffer** ([ECRITURES]; the receiver [PlutoSdr] refuses
 * them all).
 *
 * Nothing is sent to the Pluto before [demarre]: no connection at all.
 *
 * **Start.** The context is checked (AD936x, the TX DMA device and its two
 * 16-bit channels); the TX LO is powered down first, then the attenuation
 * (clamped to [gainMaxDb] and 0 dB), the sample rate, the analog band, the
 * TX LO frequency; the DDS tones are switched off; the TX buffer is opened
 * on a second connection and the first buffers sent; only then is the LO
 * powered up.
 *
 * **Stop** (asked, at the end of the time allowed, on any error, when the
 * Pluto stops taking samples, when the samples come too late too often,
 * or when [Reglage.garde] says so): the TX LO powered down first (on the
 * control connection, or a fresh one if that one is gone), then the buffer
 * closed.
 *
 * The samples come from a producer called on a thread of its own, into a
 * short queue; a buffer missing when the Pluto is ready is an underrun
 * ([Etat.manques]).
 */
class PlutoEmission(
    private val hote: String = PlutoSdr.HOTE,
    private val port: Int = PlutoSdr.PORT,
    /** Never above this TX gain (dB), whatever is asked. */
    private val gainMaxDb: Double = -10.0,
    private val attenteMs: Int = PlutoSdr.ATTENTE_MS,
    private val horloge: () -> Long = System::currentTimeMillis,
    /** No buffer taken by the Pluto for this long: stopped. */
    private val chienMs: Long = 3_000L,
    /** More underruns than this within [fenetreManquesMs]: stopped. */
    private val manquesMax: Int = 20,
    private val fenetreManquesMs: Long = 10_000L,
) : EmetteurDatv {
    data class Reglage(
        val frequenceHz: Long,
        /** Samples per second (I/Q pairs). */
        val debit: Long,
        val gainDb: Double,
        /** How long it may transmit (ms), then stops by itself. */
        val dureeMs: Long,
        /** The analog TX band (Hz); the sample rate when 0. */
        val bandeHz: Long = 0L,
        /** A lower ceiling for this transmission (the dummy load's). */
        val plafondDb: Double = 0.0,
        /** I/Q pairs per buffer. */
        val paires: Int = 65_536,
        /** Asked every 200 ms: a reason to stop, or null (the page gone, SatMe closing…). */
        val garde: () -> String? = { null },
        /** The I/Q correction for the board of this serial number (its context's hw_serial), or none. */
        val correction: (String) -> CorrectionIq? = { null },
    )

    data class Etat(
        val emet: Boolean = false,
        val debutMs: Long = 0L,
        val finMs: Long = 0L,
        val frequenceHz: Long = 0L,
        val gainDb: Double = 0.0,
        val tampons: Long = 0L,
        val manques: Long = 0L,
        /** Why the last transmission ended. */
        val raison: String = "",
        val erreur: String = "",
    )

    @Volatile var etat = Etat(); private set

    /** The board's serial number (its context's hw_serial), once [demarre] has read it. */
    @Volatile var serie: String = ""; private set

    override val nom: String get() = "ADALM-PLUTO"

    override fun etatCommun(): EtatEmission = etat.let {
        EtatEmission(it.emet, it.debutMs, it.finMs, it.frequenceHz, it.gainDb, it.tampons, it.manques, it.raison, it.erreur)
    }

    private var ctrl: Iiod? = null
    @Volatile private var flux: Iiod? = null
    @Volatile private var generation = 0
    @Volatile private var actif = false
    @Volatile private var dernierEnvoi = 0L
    private val datesManques = ArrayDeque<Long>()
    private var plafond = 0.0
    private val fils = ArrayList<Thread>()
    /** Held while the LO is switched: on, then a stop's off, in that order. */
    private val verrouLo = Any()

    /** The gain applied for [demande]: below every ceiling, in the AD936x's 0.25 dB steps (rounded down). */
    fun gainApplique(demande: Double, plafondDb: Double = 0.0): Double {
        val g = minOf(demande, gainMaxDb, plafondDb, 0.0)
        return (Math.floor(g * 4) / 4).coerceAtLeast(GAIN_MIN_DB)
    }

    /**
     * Configures and starts transmitting [r], samples from [producteur]
     * (it fills the array given: [Reglage.paires] I/Q pairs); null once
     * transmitting, otherwise why not (the LO left powered down).
     */
    @Synchronized
    fun demarre(r: Reglage, producteur: (ShortArray) -> Unit): String? {
        if (actif) return "déjà en émission"
        require(r.paires in 1024..(1 shl 20))
        val gen = ++generation
        plafond = minOf(r.plafondDb, 0.0)
        val gain = gainApplique(r.gainDb, plafond)
        val duree = r.dureeMs.coerceIn(1L, DUREE_MAX_MS)
        val c = try {
            Iiod.connecte(hote, port, attenteMs)
        } catch (x: IOException) {
            return "ADALM-PLUTO injoignable sur $hote:$port"
        }
        ctrl = c
        val masque: String
        val etage: EtageIq?
        try {
            val xml = c.contexte()
            masque = verifie(xml)
            serie = PlutoSdr.Contexte.lis(xml).attributs["hw_serial"].orEmpty()
            etage = r.correction(serie)?.let { EtageIq(it, r.frequenceHz, r.debit) }
            // Silent first, then set up.
            ecritTx(c, PHY, LO_TX, "powerdown", "1")
            ecritTx(c, PHY, TX, "hardwaregain", db(gain))
            ecritTx(c, PHY, TX, "sampling_frequency", r.debit.toString())
            ecritTx(c, PHY, TX, "rf_bandwidth", (if (r.bandeHz > 0) r.bandeHz else r.debit).coerceIn(BANDE_MIN, BANDE_MAX).toString())
            ecritTx(c, PHY, LO_TX, "frequency", (etage?.loHz ?: r.frequenceHz).toString())
            // DDS tones off (raw is shared by the four), their scale to 0: only the buffer goes out.
            for (k in 0 until 4) {
                ecritTx(c, TX_DEV, Iiod.Canal(true, "altvoltage$k"), "raw", "0")
                ecritTx(c, TX_DEV, Iiod.Canal(true, "altvoltage$k"), "scale", "0")
            }
        } catch (x: IOException) {
            eteint(c)
            c.close(); ctrl = null
            return message(x)
        }
        val maintenant = horloge()
        actif = true
        dernierEnvoi = maintenant
        synchronized(datesManques) { datesManques.clear() }
        etat = Etat(emet = true, debutMs = maintenant, finMs = maintenant + duree, frequenceHz = r.frequenceHz, gainDb = gain)
        val file = ArrayBlockingQueue<ShortArray>(PROFONDEUR)
        fils.clear()
        fils += Thread({ produit(gen, r.paires, file, producteur, etage) }, "satme-pluto-tx-mod").apply { isDaemon = true; priority = Thread.MAX_PRIORITY - 1 }
        fils += Thread({ ecrit(gen, r, masque, file) }, "satme-pluto-tx").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }
        fils += Thread({ veille(gen, r) }, "satme-pluto-tx-garde").apply { isDaemon = true }
        fils.forEach { it.start() }
        return null
    }

    /** Stops transmitting (the LO powered down first); [raison] kept in the state. */
    override fun arrete(raison: String) = coupe(generation, raison, "")

    /** A new gain while transmitting (clamped as at the start); the gain applied, or null when not transmitting. */
    override fun regleGain(db: Double): Double? {
        val c = ctrl ?: return null
        if (!actif) return null
        val g = gainApplique(db, plafond)
        return try {
            ecritTx(c, PHY, TX, "hardwaregain", db(g))
            etat = etat.copy(gainDb = g); g
        } catch (x: IOException) {
            coupe(generation, "", message(x)); null
        }
    }

    // ------------------------------------------------------------ threads

    private fun produit(gen: Int, paires: Int, file: ArrayBlockingQueue<ShortArray>, producteur: (ShortArray) -> Unit, etage: EtageIq?) {
        try {
            while (actif && generation == gen) {
                val b = ShortArray(2 * paires)
                producteur(b)
                // The correction last, just before the transmitter.
                etage?.applique(b)
                while (actif && generation == gen && !file.offer(b, 200, TimeUnit.MILLISECONDS)) { /* the Pluto sets the pace */ }
            }
        } catch (x: InterruptedException) {
            // stopping
        } catch (x: Exception) {
            coupe(gen, "", "modulateur : " + (x.message ?: x.javaClass.simpleName))
        }
    }

    private fun ecrit(gen: Int, r: Reglage, masque: String, file: ArrayBlockingQueue<ShortArray>) {
        var s: Iiod? = null
        try {
            s = Iiod.connecte(hote, port, attenteMs).also { flux = it }
            s.delai(maxOf(1000, attenteMs))
            s.ouvre(TX_DEV, r.paires, masque)
            val octets = ByteArray(4 * r.paires)
            var envoyes = 0L
            while (actif && generation == gen) {
                var b = file.poll()
                if (b == null) {
                    // The Pluto ready, the samples not: an underrun (counted once streaming).
                    if (envoyes >= AMORCE) manque()
                    b = file.poll(500, TimeUnit.MILLISECONDS) ?: continue
                }
                for (k in b.indices) { val v = b[k].toInt(); octets[2 * k] = v.toByte(); octets[2 * k + 1] = (v shr 8).toByte() }
                if (!actif || generation != gen) break
                s.ecritTampon(TX_DEV, octets, octets.size)
                envoyes++
                dernierEnvoi = horloge()
                etat = etat.copy(tampons = envoyes)
                // The first buffers in: now the LO.
                if (envoyes == AMORCE.toLong()) {
                    val c = ctrl ?: throw IOException("liaison de commande perdue")
                    // Under the LO's lock: a stop in between powers it down after this, never before.
                    synchronized(verrouLo) {
                        if (!actif || generation != gen) return
                        ecritTx(c, PHY, LO_TX, "powerdown", "0")
                    }
                }
            }
            runCatching { s.ferme(TX_DEV) }
        } catch (x: InterruptedException) {
            // stopping
        } catch (x: IOException) {
            coupe(gen, "", message(x))
        } finally {
            s?.close()
            if (flux === s) flux = null
        }
    }

    private fun manque() {
        val t = horloge()
        etat = etat.copy(manques = etat.manques + 1)
        synchronized(datesManques) {
            datesManques.addLast(t)
            while (datesManques.isNotEmpty() && t - datesManques.first() > fenetreManquesMs) datesManques.removeFirst()
        }
    }

    private fun veille(gen: Int, r: Reglage) {
        while (actif && generation == gen) {
            try { Thread.sleep(PERIODE_MS) } catch (x: InterruptedException) { return }
            val t = horloge()
            val e = etat
            val raison = when {
                t >= e.finMs -> "durée maximale atteinte"
                t - dernierEnvoi > chienMs -> null.also { coupe(gen, "", "l'ADALM-PLUTO ne prend plus les échantillons (liaison perdue ?)") }
                synchronized(datesManques) { datesManques.count { t - it <= fenetreManquesMs } } > manquesMax ->
                    null.also { coupe(gen, "", "échantillons trop souvent en retard (${e.manques} manques) : PC trop lent ?") }
                else -> runCatching { r.garde() }.getOrElse { "garde : " + it.message }
            }
            if (raison != null) coupe(gen, raison, "")
        }
    }

    /** The end, whatever its cause: LO powered down, buffer closed, connections closed. */
    private fun coupe(gen: Int, raison: String, erreur: String) {
        synchronized(this) {
            if (!actif || gen != generation) return
            actif = false
            generation++
        }
        val c = ctrl
        ctrl = null
        var eteint: Boolean
        synchronized(verrouLo) {
            eteint = c != null && eteint(c)
            c?.close()
            if (!eteint) eteint = runCatching { Iiod.connecte(hote, port, attenteMs).use { eteint(it) } }.getOrDefault(false)
        }
        // The writer closes its buffer; if it is stuck, its connection goes.
        val ecrivain = fils.getOrNull(1)
        if (ecrivain != null && ecrivain !== Thread.currentThread()) {
            runCatching { ecrivain.join(1500) }
            if (ecrivain.isAlive) flux?.close()
        }
        fils.filter { it !== Thread.currentThread() }.forEach { it.interrupt() }
        etat = etat.copy(emet = false, raison = raison.ifEmpty { "erreur" },
            erreur = erreur + (if (!eteint) (if (erreur.isEmpty()) "" else " — ") + "oscillateur d'émission NON coupé : débrancher l'ADALM-PLUTO" else ""))
    }

    private fun eteint(c: Iiod): Boolean = runCatching { ecritTx(c, PHY, LO_TX, "powerdown", "1") }.isSuccess

    /** The context: an AD936x with its TX DMA device, two 16-bit output channels; their mask. */
    private fun verifie(xml: String): String {
        val d = PlutoSdr.Contexte.lis(xml)
        if (PHY !in d.appareils || TX_DEV !in d.appareils) throw IOException("$hote:$port n'est pas un ADALM-PLUTO (pas d'émetteur AD936x)")
        return masqueTx(xml) ?: throw IOException("ADALM-PLUTO : format d'échantillons d'émission inattendu")
    }

    companion object {
        const val PHY = PlutoSdr.PHY
        const val TX_DEV = "cf-ad9361-dds-core-lpc"
        val TX = Iiod.Canal(true, "voltage0")
        val LO_TX = Iiod.Canal(true, "altvoltage1")
        /** Every write this class may make, and nobody else. */
        val ECRITURES: Set<Triple<String, Iiod.Canal, String>> = setOf(
            Triple(PHY, LO_TX, "powerdown"), Triple(PHY, LO_TX, "frequency"),
            Triple(PHY, TX, "hardwaregain"), Triple(PHY, TX, "sampling_frequency"), Triple(PHY, TX, "rf_bandwidth")) +
            (0 until 4).flatMap { k -> listOf("raw", "scale").map { Triple(TX_DEV, Iiod.Canal(true, "altvoltage$k"), it) } }
        const val GAIN_MIN_DB = -89.75
        const val BANDE_MIN = 200_000L
        const val BANDE_MAX = 56_000_000L
        const val DUREE_MAX_MS = 30 * 60_000L
        const val PROFONDEUR = 4
        /** Buffers in before the LO comes on. */
        const val AMORCE = 2
        const val PERIODE_MS = 200L

        private fun ecritTx(c: Iiod, dev: String, canal: Iiod.Canal, attr: String, v: String) {
            check(Triple(dev, canal, attr) in ECRITURES) { "écriture non prévue : $dev $canal $attr" }
            c.ecrit(dev, canal, attr, v)
        }

        fun db(g: Double) = String.format(Locale.US, "%.2f", g)

        /**
         * The TX DMA channels' mask from the context: I = voltage0, Q =
         * voltage1 (scan indices 0 and 1 on a Pluto: "00000003"), signed
         * 16-bit little-endian words, the DAC's 12 bits at the top (full
         * scale ±32767). The altvoltage tone channels have no scan element.
         */
        fun masqueTx(xml: String): String? {
            val m = Regex("<device id=\"[^\"]*\" name=\"$TX_DEV\"[^>]*>(.*?)</device>", RegexOption.DOT_MATCHES_ALL).find(xml) ?: return null
            val canaux = Regex("<channel id=\"(voltage[01])\" type=\"output\"[^>]*>\\s*<scan-element index=\"(\\d+)\" format=\"([^\"]*)\"")
                .findAll(m.groupValues[1]).map { Triple(it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3].replace("&gt;", ">")) }.toList()
            if (canaux.map { it.first }.toSet() != setOf("voltage0", "voltage1")) return null
            if (canaux.any { !Regex("le:[Ss]1[2-6]/16>>0").matches(it.third) }) return null
            if (canaux.first { it.first == "voltage0" }.second > canaux.first { it.first == "voltage1" }.second) return null
            var bits = 0L
            for (c in canaux) if (c.second < 32) bits = bits or (1L shl c.second)
            return String.format("%08x", bits)
        }

        private fun message(x: IOException): String = when (x) {
            is SocketTimeoutException -> "l'ADALM-PLUTO ne répond plus (débranché ?)"
            is EOFException -> "l'ADALM-PLUTO a coupé la liaison"
            is Iiod.Refus -> "ADALM-PLUTO : " + x.message
            else -> "ADALM-PLUTO : " + (x.message ?: "liaison perdue")
        }

        /**
         * Powers the TX LO down and nothing else (one connection, one
         * write): for the option that silences the transmitter while SatMe
         * receives with the Pluto. True when done.
         */
        fun coupeOscillateur(hote: String = PlutoSdr.HOTE, port: Int = PlutoSdr.PORT, attenteMs: Int = PlutoSdr.ATTENTE_MS): Boolean =
            runCatching { Iiod.connecte(hote, port, attenteMs).use { ecritTx(it, PHY, LO_TX, "powerdown", "1") } }.isSuccess
    }
}
