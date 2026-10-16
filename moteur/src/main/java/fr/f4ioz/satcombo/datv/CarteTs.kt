/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * A station's card, carried in its transport stream ([CarteTs]). Empty
 * fields are left out of the stream.
 */
data class CarteStation(
    val indicatif: String,
    val locator: String = "",
    val nom: String = "",
    val qth: String = "",
    val puissance: String = "",
    val antenne: String = "",
    val logiciel: String = "",
    /** The transmit frequency (kHz), 0 when unknown. */
    val frequenceKhz: Long = 0L,
    val modcod: String = "",
    val srKs: Int = 0,
    val message: String = "",
    /** The section's version_number (0..31): changes with the content. */
    val version: Int = 0,
    /**
     * Talkback: where the station listens on QO-100's narrow band (downlink,
     * Hz), 0 when not given; [retourMode] USB or CW.
     */
    val retourHz: Long = 0L,
    val retourMode: String = "",
) {
    /** The talkback, when it is a narrow band frequency. */
    val retour: Boolean get() = retourHz in fr.f4ioz.satcombo.domain.Qo100.NB.descenteBasHz..fr.f4ioz.satcombo.domain.Qo100.NB.descenteHautHz

    companion object {
        /** The talkback modes; anything else reads as USB. */
        fun modeRetour(m: String): String = if (m.trim().uppercase() == "CW") "CW" else "USB"
    }
}

/** A chat line: its sequence number (16 bits), who wrote it, the text. */
data class LigneChat(val seq: Int, val indicatif: String, val texte: String)

/**
 * The station card and the chat lines in a DATV transport stream: private
 * sections (ISO/IEC 13818-1, long form with CRC-32) on their own PID,
 * which receivers that do not know it drop. The PMT is left as it is.
 *
 * Section: table_id [TABLE_CARTE] or [TABLE_CHAT]; table_id_extension 0
 * for the card, the line's sequence number for the chat; version_number
 * the card's version; one section (section_number 0 of 0). Its payload:
 * the magic "SATM", a format byte ([FORMAT]), then TLV fields (tag, length
 * 0..255, UTF-8 value); unknown tags are skipped. A format byte of 0x80 or
 * more is a layout this reader does not know: the section is ignored.
 */
object CarteTs {
    const val PID = 0x1F00
    const val TABLE_CARTE = 0xE5
    const val TABLE_CHAT = 0xE6
    const val FORMAT = 1
    /** A chat line's length, in characters. */
    const val CHAT_MAX = 140
    /** A section's whole size at most (section_length ≤ 1021). */
    const val SECTION_MAX = 1024
    val MAGIE = byteArrayOf('S'.code.toByte(), 'A'.code.toByte(), 'T'.code.toByte(), 'M'.code.toByte())

    // The fields' tags.
    const val T_INDICATIF = 0x01
    const val T_LOCATOR = 0x02
    const val T_NOM = 0x03
    const val T_QTH = 0x04
    const val T_PUISSANCE = 0x05
    const val T_ANTENNE = 0x06
    const val T_LOGICIEL = 0x07
    const val T_FREQUENCE = 0x08
    const val T_MODCOD = 0x09
    const val T_SR = 0x0A
    const val T_MESSAGE = 0x0B
    /** Talkback on QO-100's narrow band: downlink in Hz (digits), then its mode (USB, CW). */
    const val T_RETOUR = 0x0C
    const val T_RETOUR_MODE = 0x0D
    const val T_TEXTE = 0x20

    private val TABLE_CRC = IntArray(256) { i ->
        var c = i shl 24
        repeat(8) { c = if (c and 0x80000000.toInt() != 0) (c shl 1) xor 0x04C11DB7 else c shl 1 }
        c
    }

    /** MPEG-2's CRC-32 (polynomial 0x04C11DB7, start 0xFFFFFFFF, not reflected, no final XOR). */
    fun crc32(b: ByteArray, de: Int = 0, a: Int = b.size): Int {
        var c = -1
        for (i in de until a) c = (c shl 8) xor TABLE_CRC[((c ushr 24) xor (b[i].toInt() and 0xFF)) and 0xFF]
        return c
    }

    /** [s] in UTF-8, cut at [max] bytes on a character's boundary. */
    fun utf8(s: String, max: Int): ByteArray {
        val o = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val n = Character.charCount(s.codePointAt(i))
            val b = s.substring(i, i + n).toByteArray(Charsets.UTF_8)
            if (o.size() + b.size > max) break
            o.write(b); i += n
        }
        return o.toByteArray()
    }

    /** A whole section: header, magic, format, the fields, CRC. */
    fun section(tableId: Int, extension: Int, version: Int, champs: List<Pair<Int, String>>): ByteArray {
        val p = ByteArrayOutputStream()
        p.write(MAGIE); p.write(FORMAT)
        for ((t, v) in champs) {
            if (v.isEmpty()) continue
            val b = utf8(v, 255)
            if (8 + p.size() + 2 + b.size + 4 > SECTION_MAX) break
            p.write(t); p.write(b.size); p.write(b)
        }
        val corps = p.toByteArray()
        val longueur = 5 + corps.size + 4
        val s = ByteArray(3 + longueur)
        s[0] = tableId.toByte()
        s[1] = (0xF0 or ((longueur shr 8) and 0x0F)).toByte()
        s[2] = longueur.toByte()
        s[3] = (extension shr 8).toByte(); s[4] = extension.toByte()
        s[5] = (0xC1 or ((version and 0x1F) shl 1)).toByte()
        s[6] = 0; s[7] = 0
        System.arraycopy(corps, 0, s, 8, corps.size)
        val crc = crc32(s, 0, s.size - 4)
        for (k in 0..3) s[s.size - 4 + k] = (crc ushr (24 - 8 * k)).toByte()
        return s
    }

    fun section(c: CarteStation): ByteArray = section(TABLE_CARTE, 0, c.version, listOf(
        T_INDICATIF to c.indicatif.trim().uppercase().take(16), T_LOCATOR to c.locator.trim().uppercase().take(10),
        T_NOM to c.nom.trim().take(48), T_QTH to c.qth.trim().take(48),
        T_PUISSANCE to c.puissance.trim().take(24), T_ANTENNE to c.antenne.trim().take(48), T_LOGICIEL to c.logiciel.trim().take(40),
        T_FREQUENCE to (if (c.frequenceKhz > 0) c.frequenceKhz.toString() else ""), T_MODCOD to c.modcod.trim().take(24),
        T_SR to (if (c.srKs > 0) c.srKs.toString() else ""), T_MESSAGE to c.message.trim().take(CHAT_MAX),
        T_RETOUR to (if (c.retourHz > 0) c.retourHz.toString() else ""),
        T_RETOUR_MODE to (if (c.retourHz > 0) CarteStation.modeRetour(c.retourMode) else "")))

    fun section(l: LigneChat): ByteArray = section(TABLE_CHAT, l.seq and 0xFFFF, 0, listOf(
        T_INDICATIF to l.indicatif.trim().uppercase().take(16), T_TEXTE to l.texte.trim().take(CHAT_MAX)))

    /** What a whole section says: a [CarteStation], a [LigneChat], or null (not ours, damaged, unknown). */
    fun lis(s: ByteArray): Any? {
        if (s.size < 3) return null
        val t = s[0].toInt() and 0xFF
        if (t != TABLE_CARTE && t != TABLE_CHAT) return null
        if (s[1].toInt() and 0x80 == 0) return null
        val longueur = ((s[1].toInt() and 0x0F) shl 8) or (s[2].toInt() and 0xFF)
        if (longueur < 5 + 5 + 4 || longueur > SECTION_MAX - 3 || s.size < 3 + longueur) return null
        val fin = 3 + longueur
        // The CRC over the whole section, its own included, leaves 0.
        if (crc32(s, 0, fin) != 0) return null
        for (k in 0..3) if (s[8 + k] != MAGIE[k]) return null
        if (s[12].toInt() and 0x80 != 0) return null
        val champs = HashMap<Int, String>()
        var i = 13
        val finChamps = fin - 4
        while (i + 2 <= finChamps) {
            val tag = s[i].toInt() and 0xFF
            val n = s[i + 1].toInt() and 0xFF
            if (i + 2 + n > finChamps) return null
            champs.putIfAbsent(tag, String(s, i + 2, n, Charsets.UTF_8))
            i += 2 + n
        }
        val ind = champs[T_INDICATIF].orEmpty().trim()
        if (ind.isEmpty()) return null
        val extension = ((s[3].toInt() and 0xFF) shl 8) or (s[4].toInt() and 0xFF)
        return if (t == TABLE_CHAT) {
            val texte = champs[T_TEXTE].orEmpty().trim()
            if (texte.isEmpty()) null else LigneChat(extension, ind, texte.take(CHAT_MAX))
        } else CarteStation(
            indicatif = ind, locator = champs[T_LOCATOR].orEmpty(), nom = champs[T_NOM].orEmpty(), qth = champs[T_QTH].orEmpty(),
            puissance = champs[T_PUISSANCE].orEmpty(), antenne = champs[T_ANTENNE].orEmpty(), logiciel = champs[T_LOGICIEL].orEmpty(),
            frequenceKhz = champs[T_FREQUENCE]?.trim()?.toLongOrNull() ?: 0L, modcod = champs[T_MODCOD].orEmpty(),
            srKs = champs[T_SR]?.trim()?.toIntOrNull() ?: 0, message = champs[T_MESSAGE].orEmpty(),
            version = ((s[5].toInt() and 0xFF) shr 1) and 0x1F,
            retourHz = champs[T_RETOUR]?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: 0L,
            retourMode = champs[T_RETOUR]?.let { CarteStation.modeRetour(champs[T_RETOUR_MODE].orEmpty()) }.orEmpty())
    }

    /**
     * [s] cut into transport packets on [pid]: the first starts the section
     * (payload_unit_start, pointer_field 0), the last filled with 0xFF. The
     * continuity counters are set when the packets go out.
     */
    fun paquets(s: ByteArray, pid: Int = PID): List<ByteArray> {
        val l = ArrayList<ByteArray>()
        var i = 0
        var premier = true
        while (i < s.size || premier) {
            val p = ByteArray(188) { 0xFF.toByte() }
            p[0] = 0x47
            p[1] = ((if (premier) 0x40 else 0) or ((pid shr 8) and 0x1F)).toByte()
            p[2] = pid.toByte()
            p[3] = 0x10
            var k = 4
            if (premier) { p[k++] = 0; premier = false }
            val n = minOf(188 - k, s.size - i)
            System.arraycopy(s, i, p, k, n)
            i += n
            l += p
        }
        return l
    }

    fun pid(p: ByteArray, de: Int = 0): Int = ((p[de + 1].toInt() and 0x1F) shl 8) or (p[de + 2].toInt() and 0xFF)

    const val PID_NUL = 0x1FFF
}

/**
 * Puts the card (every [periodeCarteMs]) and the chat lines (each
 * [repetitionsChat] times, [periodeChatMs] apart) into a transport stream
 * in place of its null packets: as many packets as before, the rate
 * exactly the same, nothing else touched. Without null packets, nothing
 * goes in (the stream is never delayed for it).
 */
class InserteurCarte(
    private val horloge: () -> Long = System::currentTimeMillis,
    private val periodeCarteMs: Long = 2000L,
    private val repetitionsChat: Int = 5,
    private val periodeChatMs: Long = 1000L,
    private val pid: Int = CarteTs.PID,
) {
    private class Envoi(val paquets: List<ByteArray>, var reste: Int, var prochaine: Long)

    private val enAttente = ArrayDeque<ByteArray>()
    private val chats = ArrayList<Envoi>()
    private var paquetsCarte: List<ByteArray> = emptyList()
    private var prochaineCarte = 0L
    private var cc = 0
    private var version = 0
    private var derniere: CarteStation? = null

    /** Packets put in place of null ones. */
    @Volatile var remplaces = 0L; private set

    /** The card sent, its version moved on when its content changes; null: none. */
    var carte: CarteStation?
        @Synchronized get() = derniere
        @Synchronized set(c) {
            if (c == null) { derniere = null; paquetsCarte = emptyList(); return }
            val avant = derniere
            if (avant != null && avant.copy(version = 0) == c.copy(version = 0)) return
            if (avant != null) version = (version + 1) and 0x1F
            val v = c.copy(version = version)
            derniere = v
            paquetsCarte = CarteTs.paquets(CarteTs.section(v), pid)
            prochaineCarte = 0L
        }

    /** A chat line to send (sent [repetitionsChat] times from now). */
    @Synchronized
    fun ajoute(l: LigneChat) {
        chats += Envoi(CarteTs.paquets(CarteTs.section(l), pid), repetitionsChat, 0L)
    }

    /** [p] (188 bytes) as it goes out: a null packet may become one of ours. */
    @Synchronized
    fun traite(p: ByteArray): ByteArray {
        if (p.size != 188 || p[0].toInt() != 0x47 || CarteTs.pid(p) != CarteTs.PID_NUL) return p
        if (enAttente.isEmpty()) planifie()
        val q = enAttente.removeFirstOrNull() ?: return p
        val r = q.copyOf()
        r[3] = (0x10 or cc).toByte()
        cc = (cc + 1) and 0x0F
        remplaces++
        return r
    }

    private fun planifie() {
        val t = horloge()
        val it = chats.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (t < e.prochaine) continue
            enAttente.addAll(e.paquets)
            e.prochaine = t + periodeChatMs
            if (--e.reste <= 0) it.remove()
        }
        if (paquetsCarte.isNotEmpty() && t >= prochaineCarte) {
            enAttente.addAll(paquetsCarte)
            prochaineCarte = t + periodeCarteMs
        }
    }
}

/**
 * Finds the card and the chat lines in a received transport stream, fed
 * in blocks of any size: packets cut across blocks, lost packets
 * (continuity counter), damaged sections (CRC) and other streams are all
 * taken in stride.
 */
class LecteurCarte(private val horloge: () -> Long = System::currentTimeMillis, private val pid: Int = CarteTs.PID) {
    companion object {
        /** QO-100's wide band transponder, downlink (kHz). */
        val QO100_LARGE_KHZ = 10_490_000L..10_500_000L
    }

    data class Recue(val ligne: LigneChat, val ms: Long)

    @Volatile var carte: CarteStation? = null; private set
    @Volatile var carteMs = 0L; private set
    @Volatile var sections = 0L; private set
    @Volatile var rejetees = 0L; private set
    private val lignes = ArrayList<Recue>()

    private val reste = ByteArray(188)
    private var nReste = 0
    private val section = ByteArrayOutputStream()
    private var dedans = false
    private var dernierCc = -1

    /** The chat lines received, oldest first, each once. */
    fun chat(): List<Recue> = synchronized(lignes) { lignes.toList() }

    /** Forgets everything (a new reception). */
    @Synchronized
    fun oublie() {
        carte = null; carteMs = 0L; synchronized(lignes) { lignes.clear() }
        nReste = 0; dedans = false; section.reset(); dernierCc = -1
    }

    @Synchronized
    fun bloc(b: ByteArray, n: Int = b.size) {
        var i = 0
        // A packet begun in the last block.
        if (nReste > 0) {
            val k = minOf(188 - nReste, n)
            System.arraycopy(b, 0, reste, nReste, k)
            nReste += k; i = k
            if (nReste < 188) return
            nReste = 0
            if (reste[0].toInt() == 0x47) paquet(reste, 0)
        }
        while (i < n) {
            // In step: a sync byte here and 188 bytes further (when there).
            if (b[i].toInt() != 0x47 || (n - i > 188 && b[i + 188].toInt() != 0x47)) { i++; continue }
            if (n - i < 188) { System.arraycopy(b, i, reste, 0, n - i); nReste = n - i; return }
            paquet(b, i)
            i += 188
        }
    }

    private fun paquet(b: ByteArray, o: Int) {
        if (CarteTs.pid(b, o) != pid) return
        val b1 = b[o + 1].toInt() and 0xFF
        val b3 = b[o + 3].toInt() and 0xFF
        if (b1 and 0x80 != 0) { perd(); return }
        val afc = (b3 shr 4) and 3
        if (afc == 0 || afc == 2) return
        val cc = b3 and 0x0F
        if (dernierCc >= 0 && cc == dernierCc) return
        if (dernierCc >= 0 && cc != ((dernierCc + 1) and 0x0F)) perd()
        dernierCc = cc
        var i = o + 4
        if (afc == 3) i += 1 + (b[o + 4].toInt() and 0xFF)
        val fin = o + 188
        if (i >= fin) return
        if (b1 and 0x40 != 0) {
            val pointeur = b[i].toInt() and 0xFF
            i++
            if (dedans) { section.write(b, i, minOf(pointeur, fin - i)); termine() }
            i += pointeur
            dedans = false; section.reset()
            // One or more sections from here; 0xFF is stuffing.
            while (i < fin && (b[i].toInt() and 0xFF) != 0xFF) {
                dedans = true
                val avant = section.size()
                section.write(b, i, fin - i)
                val pris = termine()
                if (pris < 0) break
                i += pris - avant
            }
        } else if (dedans) {
            section.write(b, i, fin - i)
            termine()
        }
    }

    /** A section complete in the buffer: read; returns the bytes it took, or −1 when it is not whole yet. */
    private fun termine(): Int {
        val s = section.toByteArray()
        if (s.size < 3) return -1
        val total = 3 + (((s[1].toInt() and 0x0F) shl 8) or (s[2].toInt() and 0xFF))
        if (total > CarteTs.SECTION_MAX) { perd(); return -1 }
        if (s.size < total) return -1
        dedans = false; section.reset()
        when (val r = CarteTs.lis(s.copyOf(total))) {
            is CarteStation -> { carte = r; carteMs = horloge(); sections++ }
            is LigneChat -> { sections++; recoit(r) }
            else -> rejetees++
        }
        return total
    }

    private fun recoit(l: LigneChat) {
        synchronized(lignes) {
            if (lignes.any { it.ligne.seq == l.seq && it.ligne.indicatif.equals(l.indicatif, true) && it.ligne.texte == l.texte }) return
            lignes += Recue(l, horloge())
            while (lignes.size > 50) lignes.removeAt(0)
        }
    }

    /**
     * For the page: the card (with the station's country, its distance and
     * bearing from [moi], how many contacts with it in the log) and the
     * chat lines. [cielKhz]: where we receive (QO-100's wide band or not).
     */
    fun json(maintenant: Long, moi: fr.f4ioz.satcombo.data.Observer?, contacts: (String) -> Int, cielKhz: Long): JSONObject {
        val o = JSONObject()
        val c = carte
        o.put("recue", c != null).put("qo100", cielKhz in QO100_LARGE_KHZ)
        if (c != null) {
            o.put("age", (maintenant - carteMs) / 1000).put("indicatif", c.indicatif).put("locator", c.locator).put("nom", c.nom)
                .put("qth", c.qth).put("puissance", c.puissance).put("antenne", c.antenne).put("logiciel", c.logiciel)
                .put("khz", c.frequenceKhz).put("modcod", c.modcod).put("sr", c.srKs).put("message", c.message).put("version", c.version)
                .put("contacts", contacts(c.indicatif))
            // Talkback on the narrow band: shown only when it is one.
            if (c.retour) o.put("retourHz", c.retourHz).put("retourMode", c.retourMode)
                .put("retourUrgence", fr.f4ioz.satcombo.domain.GardeQo100.urgence(c.retourHz))
            fr.f4ioz.satcombo.domain.Dxcc.entite(c.indicatif)?.let { o.put("pays", it.nom).put("drapeau", it.drapeau) }
            if (c.locator.isNotBlank()) fr.f4ioz.satcombo.demo.ApiStation.ouEstIl(o, moi, null, null, c.locator)
        }
        o.put("chat", JSONArray(chat().map { r -> JSONObject().put("seq", r.ligne.seq).put("c", r.ligne.indicatif).put("texte", r.ligne.texte).put("t", r.ms) }))
        return o
    }

    private fun perd() {
        if (dedans) rejetees++
        dedans = false; section.reset()
    }
}
