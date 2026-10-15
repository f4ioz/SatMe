/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import java.io.ByteArrayOutputStream

/**
 * PSK Reporter's reporting format: IPFIX (RFC 7011) over UDP, with the
 * receiver and sender records its public description defines (enterprise
 * number 30351). Pure bytes: no network here.
 */
object PskReporterIpfix {

    const val HOTE = "report.pskreporter.info"
    const val PORT = 4739
    /** Kept under a common path MTU. */
    const val TAILLE_MAX = 1400

    const val GABARIT_RECEPTEUR = 0x9992
    const val GABARIT_EMETTEUR = 0x9993

    /** Who listens: one record per datagram. */
    data class Recepteur(val indicatif: String, val locator: String, val logiciel: String, val antenne: String)

    /** One station heard. [frequenceHz] on five bytes (QO-100 is above 4.29 GHz). */
    data class Spot(val indicatif: String, val frequenceHz: Long, val snrDb: Int, val mode: String, val locator: String, val instantS: Long)

    private const val ENTREPRISE = 30351L

    /** Options template (set 3): receiverCallsign, receiverLocator, decodingSoftware, antennaInformation. */
    fun gabaritRecepteur(): ByteArray {
        val c = Ecrit()
        c.u16(3); c.u16(0) // length patched below
        c.u16(GABARIT_RECEPTEUR); c.u16(4); c.u16(0)
        for (id in intArrayOf(2, 4, 8, 9)) champEntreprise(c, id, 0xFFFF)
        return c.ensemble()
    }

    /** Template (set 2): senderCallsign, frequency, sNR, mode, informationSource, flowStartSeconds, senderLocator. */
    fun gabaritEmetteur(): ByteArray {
        val c = Ecrit()
        c.u16(2); c.u16(0)
        c.u16(GABARIT_EMETTEUR); c.u16(7)
        champEntreprise(c, 1, 0xFFFF)
        champEntreprise(c, 5, 5)
        champEntreprise(c, 6, 1)
        champEntreprise(c, 10, 0xFFFF)
        champEntreprise(c, 11, 1)
        c.u16(150); c.u16(4)
        champEntreprise(c, 3, 0xFFFF)
        return c.ensemble()
    }

    fun donneesRecepteur(r: Recepteur): ByteArray {
        val c = Ecrit()
        c.u16(GABARIT_RECEPTEUR); c.u16(0)
        c.texte(r.indicatif); c.texte(r.locator); c.texte(r.logiciel); c.texte(r.antenne)
        return c.ensemble()
    }

    fun donneesSpots(spots: List<Spot>): ByteArray {
        val c = Ecrit()
        c.u16(GABARIT_EMETTEUR); c.u16(0)
        for (s in spots) enregistrement(c, s)
        return c.ensemble()
    }

    /** One sender record, as it sits in a data set. */
    fun tailleSpot(s: Spot): Int = Ecrit().also { enregistrement(it, s) }.size()

    private fun enregistrement(c: Ecrit, s: Spot) {
        c.texte(s.indicatif)
        val f = s.frequenceHz.coerceIn(0L, 0xFF_FFFF_FFFFL)
        c.u8((f ushr 32).toInt()); c.u32(f and 0xFFFF_FFFFL)
        c.u8(s.snrDb.coerceIn(-128, 127) and 0xFF)
        c.texte(s.mode)
        c.u8(1) // informationSource: automatic
        c.u32(s.instantS and 0xFFFF_FFFFL)
        c.texte(s.locator)
    }

    /** The message: header, the templates when asked, the receiver, the senders. */
    fun paquet(r: Recepteur, spots: List<Spot>, exportS: Long, sequence: Long, domaine: Int, avecGabarits: Boolean): ByteArray {
        val corps = ByteArrayOutputStream()
        if (avecGabarits) { corps.write(gabaritRecepteur()); corps.write(gabaritEmetteur()) }
        corps.write(donneesRecepteur(r))
        if (spots.isNotEmpty()) corps.write(donneesSpots(spots))
        val c = Ecrit()
        c.u16(10); c.u16(16 + corps.size())
        c.u32(exportS and 0xFFFF_FFFFL); c.u32(sequence and 0xFFFF_FFFFL); c.u32(domaine.toLong() and 0xFFFF_FFFFL)
        c.o.write(corps.toByteArray())
        return c.o.toByteArray()
    }

    /** The spots cut into datagrams under [TAILLE_MAX], with room left for the templates in each. */
    fun decoupe(r: Recepteur, spots: List<Spot>): List<List<Spot>> {
        val fixe = 16 + donneesRecepteur(r).size + 4 + 3 + gabaritRecepteur().size + gabaritEmetteur().size
        val out = ArrayList<List<Spot>>()
        var courant = ArrayList<Spot>(); var taille = fixe
        for (s in spots) {
            val t = tailleSpot(s)
            if (courant.isNotEmpty() && taille + t > TAILLE_MAX) { out += courant; courant = ArrayList(); taille = fixe }
            courant += s; taille += t
        }
        if (courant.isNotEmpty()) out += courant
        return out
    }

    private fun champEntreprise(c: Ecrit, id: Int, longueur: Int) {
        c.u16(0x8000 or id); c.u16(longueur); c.u32(ENTREPRISE)
    }

    private class Ecrit {
        val o = ByteArrayOutputStream()
        fun u8(v: Int) = o.write(v and 0xFF)
        fun u16(v: Int) { o.write((v ushr 8) and 0xFF); o.write(v and 0xFF) }
        fun u32(v: Long) { u16(((v ushr 16) and 0xFFFF).toInt()); u16((v and 0xFFFF).toInt()) }
        fun texte(s: String) {
            val b = s.toByteArray(Charsets.UTF_8).let { if (it.size > 254) it.copyOf(254) else it }
            u8(b.size); o.write(b)
        }
        fun size() = o.size()
        /** A set: its length written in, padded to four bytes. */
        fun ensemble(): ByteArray {
            while (o.size() % 4 != 0) o.write(0)
            val b = o.toByteArray()
            b[2] = (b.size ushr 8).toByte(); b[3] = b.size.toByte()
            return b
        }
    }
}

/**
 * FT8 / FT4 stations heard, sent to PSK Reporter in batches of five minutes.
 * Off unless chosen; a station once per mode in a batch; hashed or odd calls,
 * our own, and decodes without a known frequency never leave. [envoie] takes
 * each datagram (a UDP socket in use, a list in the tests).
 */
class PskReporter(
    private val envoie: (ByteArray) -> Unit,
    private val horloge: () -> Long = System::currentTimeMillis,
    private val domaine: Int = java.util.Random().nextInt(),
) {
    data class Entendu(val indicatif: String, val locator: String, val mode: String, val snrDb: Int,
                       val frequenceHz: Long, val instantMs: Long, val antenne: String)

    private val lot = LinkedHashMap<String, Entendu>()
    private var debutLot = 0L
    private var sequence = 0L
    private var paquets = 0
    private var gabaritsMs = 0L

    /** For the settings' line. */
    @Volatile var etat: String = ""; private set
    @Volatile var dernierEnvoiMs: Long = 0L; private set
    @Volatile var dernierNombre: Int = 0; private set
    val enAttente: Int @Synchronized get() = lot.size

    /** A decode: kept for the batch when it may leave. True when kept. */
    @Synchronized
    fun ajoute(indicatif: String?, locator: String?, mode: String, snrDb: Int, frequenceHz: Long?,
               instantMs: Long, antenne: String, monIndicatif: String): Boolean {
        val ind = indicatif?.trim()?.uppercase() ?: return false
        if (!estIndicatif(ind) || frequenceHz == null || frequenceHz <= 0) return false
        if (monIndicatif.isNotBlank() && base(ind) == base(monIndicatif.trim().uppercase())) return false
        val m = mode.trim().uppercase().ifBlank { return false }
        val loc = locator?.trim()?.uppercase()?.takeIf { Regex("[A-R]{2}[0-9]{2}([A-X]{2})?").matches(it) && it != "RR73" }
        val cle = "$ind|$m"
        val avant = lot.remove(cle)
        if (lot.isEmpty() && avant == null) debutLot = horloge()
        lot[cle] = Entendu(ind, loc ?: avant?.locator.orEmpty(), m, snrDb, frequenceHz, instantMs, antenne)
        return true
    }

    /** Off: the batch dropped, nothing sent. */
    @Synchronized fun vide() { lot.clear(); etat = "" }

    /**
     * Called every few seconds: the batch sent once five minutes old. Without a
     * callsign nothing leaves (the batch is dropped).
     */
    @Synchronized
    fun tic(actif: Boolean, indicatif: String, locator: String, logiciel: String) {
        if (!actif) { lot.clear(); return }
        if (lot.isEmpty()) return
        val maintenant = horloge()
        if (maintenant - debutLot < LOT_MS) return
        val tous = lot.values.toList()
        lot.clear()
        if (indicatif.isBlank()) { etat = "Indicatif manquant (Réglages) : rien n'est envoyé"; return }
        var envoyes = 0
        try {
            for ((antenne, spots) in tous.groupBy { it.antenne }) {
                val r = PskReporterIpfix.Recepteur(indicatif.trim().uppercase(), locator.trim(), logiciel, antenne)
                val l = spots.map { PskReporterIpfix.Spot(it.indicatif, it.frequenceHz, it.snrDb, it.mode, it.locator, it.instantMs / 1000) }
                for (morceau in PskReporterIpfix.decoupe(r, l)) {
                    val g = gabaritsDus(maintenant)
                    val p = PskReporterIpfix.paquet(r, morceau, maintenant / 1000, sequence, domaine, g)
                    envoie(p)
                    if (g) gabaritsMs = maintenant
                    paquets++; sequence += morceau.size + 1; envoyes += morceau.size
                }
            }
            dernierEnvoiMs = maintenant; dernierNombre = envoyes
            val heure = java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(maintenant))
            etat = "Dernier envoi à $heure UTC : $envoyes station" + (if (envoyes > 1) "s" else "")
        } catch (e: Exception) {
            etat = "Envoi impossible : " + (e.message ?: e.javaClass.simpleName)
        }
    }

    /** Templates in the first three datagrams, then once an hour. */
    private fun gabaritsDus(maintenant: Long) = paquets < 3 || maintenant - gabaritsMs >= GABARITS_MS

    companion object {
        const val LOT_MS = 5 * 60_000L
        const val GABARITS_MS = 3_600_000L

        private val CORPS = Regex("[A-Z0-9]{0,2}[A-Z][0-9]{1,2}[A-Z]{1,4}")
        private val AJOUT = Regex("[A-Z0-9]{1,4}")

        /** A plausible callsign, a prefix or suffix after a slash allowed; no hashed <...>, no CQ DX, no POTA. */
        fun estIndicatif(s: String): Boolean {
            val p = s.trim().uppercase().split('/')
            if (p.size > 3 || s.length > 13) return false
            val i = p.indices.maxByOrNull { p[it].length } ?: return false
            if (!CORPS.matches(p[i])) return false
            return p.indices.all { it == i || AJOUT.matches(p[it]) }
        }

        private fun base(s: String) = s.split('/').maxByOrNull { it.length }.orEmpty()

        private fun estQo100(hz: Long) = hz in 10_489_000_000L..10_500_000_000L

        /**
         * Where a decode was heard (Hz), null when not known for sure: the rig
         * must be on CAT. QO-100 on the rig: its downlink (the rig's dial when
         * it reads as one, else the QO-100 screen's) plus the audio offset. A
         * satellite followed: the dial. Nothing followed: dial plus offset.
         */
        fun frequence(catConnecte: Boolean, descenteCatHz: Long?, qo100AuPoste: Boolean, qo100DescenteHz: Long,
                      satelliteSuivi: Boolean, audioHz: Int): Long? {
            if (!catConnecte) return null
            if (qo100AuPoste) {
                val dial = descenteCatHz?.takeIf { estQo100(it) } ?: qo100DescenteHz.takeIf { estQo100(it) } ?: return null
                return dial + audioHz
            }
            val dial = descenteCatHz?.takeIf { it > 0 } ?: return null
            return if (satelliteSuivi) dial else dial + audioHz
        }

        /** The SDR dongle following QO-100: its downlink plus the audio offset; nothing else is known for sure. */
        fun frequenceCle(qo100ALaCle: Boolean, qo100DescenteHz: Long, audioHz: Int): Long? =
            if (qo100ALaCle && estQo100(qo100DescenteHz)) qo100DescenteHz + audioHz else null

        /** The antenna field: the satellite followed. */
        fun antenne(qo100: Boolean, satellite: String?): String = if (qo100) "QO-100" else satellite?.trim().orEmpty()
    }
}

/**
 * The datagrams to PSK Reporter, sent from a thread of their own (never the
 * caller's: the phone's main thread must not touch the network).
 */
class EnvoiUdp(private val hote: String = PskReporterIpfix.HOTE, private val port: Int = PskReporterIpfix.PORT) : (ByteArray) -> Unit {
    private val fil = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "satme-psk").apply { isDaemon = true } }
    @Volatile var erreur: String = ""; private set

    override fun invoke(p: ByteArray) {
        fil.execute {
            erreur = runCatching {
                java.net.DatagramSocket().use { s ->
                    s.send(java.net.DatagramPacket(p, p.size, java.net.InetAddress.getByName(hote), port))
                }
            }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }.orEmpty()
        }
    }

    /** Waits for what is queued (the tests). */
    fun attends(ms: Long) {
        val f = fil.submit { }
        runCatching { f.get(ms, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }
}
