/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The Codec2 voice of AMSAT-EA's GENESIS satellites (HADES-SA / SO-127:
 * the BBS's recorded messages), receive only. The format as AMSAT-EA
 * publishes it (« HADES-SA_SpinnyONE », github.com/AMSAT-EA, CC BY 4.0,
 * © AMSAT EA — the key below is their published constant):
 *
 * - a GENESIS frame of type 11, size byte 37, **no CRC**: the type/address
 *   byte, the packet's **number** (0, 1, 2…), then 35 bytes **not
 *   scrambled** but xored with a fixed 35-byte key;
 * - those 35 bytes are ten Codec2 **700C** frames of 28 bits (MSB first),
 *   400 ms of speech a packet;
 * - in a `.c2` file each frame takes 4 bytes (the 28 bits, then 4 zero bits).
 *
 * [AssemblageVoix] puts a message together: packets by number, a missing one
 * as 40 zero bytes (muted when made into sound), a new message when the
 * number starts again or after [SILENCE_MS] without voice; a number more
 * than [SAUT_MAX] past the last one is dropped (no CRC: a bit wrong in it).
 * A message is written, and listed, from [MIN_PAQUETS] packets on: noise
 * that looks like one lone voice frame is no message.
 */
object VoixGenesis {
    /** The size byte of a voice frame (type/address, number, 35 bytes; no CRC). */
    const val TAILLE = 37
    const val OCTETS = 35
    const val TRAMES_PAR_PAQUET = 10
    const val BITS_700C = 28
    /** Bytes of one 700C frame in a `.c2` file. */
    const val OCTETS_C2 = 4
    const val MS_PAR_PAQUET = 400

    /** AMSAT-EA's published key (HADES-SA_SpinnyONE, CC BY 4.0, © AMSAT EA). */
    private val CLE = "ed15d53b3470e0fded8390dbaa2e25d65e814186bd67795d70a113ce500c19cafb440d"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** The 35 bytes as received → the ten 700C frames in `.c2` layout (40 bytes). */
    fun c2(recus: ByteArray): ByteArray {
        require(recus.size == OCTETS)
        val clair = ByteArray(OCTETS) { (recus[it].toInt() xor CLE[it].toInt()).toByte() }
        val out = ByteArray(TRAMES_PAR_PAQUET * OCTETS_C2)
        for (t in 0 until TRAMES_PAR_PAQUET) {
            var v = 0L
            for (k in 0 until BITS_700C) {
                val i = t * BITS_700C + k
                v = (v shl 1) or ((clair[i / 8].toLong() ushr (7 - i % 8)) and 1)
            }
            v = v shl (OCTETS_C2 * 8 - BITS_700C)
            for (k in 0 until OCTETS_C2) out[t * OCTETS_C2 + k] = (v ushr (8 * (OCTETS_C2 - 1 - k))).toByte()
        }
        return out
    }

    /** [c2] reversed (the tests, the generator): ten 4-byte 700C frames → the 35 bytes sent. */
    fun envoye(c2: ByteArray): ByteArray {
        val clair = ByteArray(OCTETS)
        for (t in 0 until TRAMES_PAR_PAQUET) {
            var v = 0L
            for (k in 0 until OCTETS_C2) v = (v shl 8) or (c2[t * OCTETS_C2 + k].toLong() and 0xFF)
            v = v ushr (OCTETS_C2 * 8 - BITS_700C)
            for (k in 0 until BITS_700C) {
                val i = t * BITS_700C + k
                if ((v ushr (BITS_700C - 1 - k)) and 1L == 1L) clair[i / 8] = (clair[i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
            }
        }
        return ByteArray(OCTETS) { (clair[it].toInt() xor CLE[it].toInt()).toByte() }
    }

    const val SILENCE_MS = 120_000L
    /** The most a number may jump ahead within a message. */
    const val SAUT_MAX = 10
    /** The fewest packets a message is kept with (no CRC: one alone may be noise). */
    const val MIN_PAQUETS = 2
}

/** Voice messages put together, written as `telemesure/genesis/voix/<UTC>-<sat>.c2` with a `.json` beside. */
class AssemblageVoix(private val dossier: File?) {

    class Message(val nom: String, val satellite: String, val debut: Long, val essai: Boolean) {
        val paquets = java.util.TreeMap<Int, ByteArray>()
        var dernier = debut
        var miseAJour = debut
        fun manquants(): List<Int> = if (paquets.isEmpty()) emptyList() else (0..paquets.lastKey()).filter { it !in paquets }
        fun dureeS(): Double = if (paquets.isEmpty()) 0.0 else (paquets.lastKey() + 1) * VoixGenesis.MS_PAR_PAQUET / 1000.0
        /** The `.c2` bytes: packet 0 to the last, a missing one as zeros. */
        fun c2(): ByteArray {
            if (paquets.isEmpty()) return ByteArray(0)
            val out = java.io.ByteArrayOutputStream()
            for (k in 0..paquets.lastKey()) out.write(paquets[k] ?: ByteArray(VoixGenesis.TRAMES_PAR_PAQUET * VoixGenesis.OCTETS_C2))
            return out.toByteArray()
        }
        fun json(): JSONObject = JSONObject().put("nom", nom).put("sat", satellite).put("debut", debut).put("miseAJour", miseAJour)
            .put("recus", JSONArray(paquets.keys.toList())).put("manquants", JSONArray(manquants())).put("duree", dureeS())
            .put("essai", essai)
    }

    @Volatile var courant: Message? = null; private set
    var paquets = 0; private set

    /** A packet [numero] heard at [t]: true when it is new (a repeat of one held is dropped). */
    @Synchronized
    fun ajoute(numero: Int, recus: ByteArray, t: Long, satellite: String, essai: Boolean = false): Boolean {
        val c2 = VoixGenesis.c2(recus)
        var m = courant
        val deja = m?.paquets?.get(numero)
        if (deja != null && deja.contentEquals(c2)) { m!!.dernier = t; return false }
        val nouveau = m == null || t - m.dernier > VoixGenesis.SILENCE_MS || m.satellite != satellite || m.essai != essai ||
            deja != null || (numero == 0 && m.paquets.keys.any { it > 0 })
        // No CRC: a number far past the message's last one is a bit wrong in it, not a packet (a message is 15 s, ~38 packets).
        if (!nouveau && numero > m!!.paquets.lastKey() + VoixGenesis.SAUT_MAX) return false
        if (nouveau) {
            val f = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            m = Message(f.format(java.util.Date(t)) + "-" + RecepteurPaquets.nomFichier(satellite.ifBlank { "inconnu" }), satellite, t, essai)
            courant = m
        }
        m!!.paquets[numero] = c2
        m.dernier = t; m.miseAJour = t
        paquets++
        if (!essai && m.paquets.size >= VoixGenesis.MIN_PAQUETS) ecrit(m)
        return true
    }

    private fun ecrit(m: Message) {
        val d = File(dossier ?: return, DOSSIER).apply { mkdirs() }
        runCatching {
            File(d, m.nom + ".c2").writeBytes(m.c2())
            File(d, m.nom + ".json").writeText(m.json().toString())
        }
    }

    companion object {
        const val DOSSIER = "telemesure/genesis/voix"

        /** The messages kept on disk, newest first. */
        fun liste(dossier: File?, max: Int = 20): List<JSONObject> {
            val d = File(dossier ?: return emptyList(), DOSSIER)
            return d.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
                .sortedByDescending { it.name }.mapNotNull { runCatching { JSONObject(it.readText()) }.getOrNull() }
                .filter { (it.optJSONArray("recus")?.length() ?: 0) >= VoixGenesis.MIN_PAQUETS }.take(max)
        }

        /** A message's `.c2` file by name (checked: no path). */
        fun fichier(dossier: File?, nom: String, ext: String = ".c2"): File? {
            if (!Regex("[A-Za-z0-9_.-]+").matches(nom) || nom.contains("..")) return null
            return File(dossier ?: return null, "$DOSSIER/$nom$ext").takeIf { it.isFile }
        }
    }
}
