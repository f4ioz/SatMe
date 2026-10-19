/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat.icomreseau

/**
 * The Icom LAN server's packets, encoded and decoded, nothing else: no
 * socket, no state.
 *
 * Icom never published this protocol. The layouts below come from public
 * descriptions made from network captures (field tables, annotated hex
 * dumps). Every offset is a named constant so a real trace can correct one
 * without touching the logic.
 *
 * Every packet starts with a 16-byte header:
 * `00` length (u32 LE), `04` type (u16 LE), `06` sequence (u16 LE),
 * `08` sender id, `0C` receiver id (u32, copied as they come).
 */
object Paquets {

    const val ENTETE = 0x10

    // Header types.
    const val T_DONNEES = 0x00
    const val T_REPRISE = 0x01
    const val T_ES_TU_LA = 0x03
    const val T_JE_SUIS_LA = 0x04
    const val T_DECONNEXION = 0x05
    const val T_PRET = 0x06
    const val T_PING = 0x07

    // Ping: request (0) / reply (1) at 0x10, a 4-byte value echoed at 0x11.
    const val PING_LONGUEUR = 0x15

    // Token family (control port): a 0x40-byte common block.
    const val JETON_LONGUEUR = 0x40
    const val J_TAILLE = 0x10          // payload size, u32 BE (= length - 0x10)
    const val J_SENS = 0x14            // 1 host request, 2 radio reply, 3 radio notice
    const val J_GENRE = 0x15           // what it is about (see G_*)
    const val J_SEQ_INTERNE = 0x16     // u16 BE
    const val J_DEMANDE_JETON = 0x1A   // u16, chosen by the host at login
    const val J_JETON = 0x1C           // u32, given by the radio
    const val J_IDENTITE = 0x20        // 16 bytes: the radio's identity, echoed
    const val J_ERREUR = 0x30          // u32: zero when accepted

    const val SENS_DEMANDE = 1
    const val SENS_REPONSE = 2
    const val SENS_AVIS = 3

    const val G_CONNEXION = 0x00
    const val G_RETRAIT = 0x01
    const val G_CREATION = 0x02
    const val G_INFO = 0x03
    const val G_RENOUVELLEMENT = 0x05

    // Login (0x80): user at 0x40, password at 0x50 (both coded), computer name at 0x60 (plain).
    const val CONNEXION_LONGUEUR = 0x80
    const val C_UTILISATEUR = 0x40
    const val C_MOT_DE_PASSE = 0x50
    const val C_NOM = 0x60
    const val CHAMP = 16

    // Login reply (0x60): the network type in clear at 0x40 ("FTTH").
    const val REPONSE_CONNEXION_LONGUEUR = 0x60
    const val RC_RESEAU = 0x40

    // Capabilities: count (u16 BE) at 0x40, entries of 0x66 bytes from 0x42.
    const val CAPACITES_ENTREE = 0x66
    const val K_NOMBRE = 0x40
    const val K_PREMIERE = 0x42
    const val KE_NOM = 0x10            // 32 bytes
    const val KE_AUDIO = 0x30          // 32 bytes
    const val KE_TYPE = 0x50
    const val KE_CIV = 0x52
    const val KE_RX = 0x53             // rate bitmap, u16 LE
    const val KE_TX = 0x55
    const val KE_VITESSE = 0x5A        // u32 BE

    // Connection info (0x90): radio name at 0x40 (32), from the radio a busy byte at 0x60;
    // from the host the coded user at 0x60 then the stream request.
    const val INFO_LONGUEUR = 0x90
    const val I_NOM = 0x40
    const val I_OCCUPE = 0x60
    const val I_UTILISATEUR = 0x60
    const val I_RX_ACTIF = 0x70
    const val I_TX_ACTIF = 0x71
    const val I_RX_CODEC = 0x72
    const val I_TX_CODEC = 0x73
    const val I_RX_TAUX = 0x74         // u32 BE, Hz
    const val I_TX_TAUX = 0x78
    const val I_PORT_CIV = 0x7C        // u32 BE
    const val I_PORT_AUDIO = 0x80
    const val I_TAMPON_TX = 0x84
    const val I_CONVERSION = 0x88

    // Status (0x50): the ports the radio gives, u32 BE.
    const val STATUT_LONGUEUR = 0x50
    const val S_PORT_CIV = 0x40
    const val S_PORT_AUDIO = 0x44

    // CI-V stream: open/close (0x16) and CI-V frames (0x15 + frame).
    const val OUVRE_LONGUEUR = 0x16
    const val CIV_MARQUE = 0xC1
    const val OUVRE_MARQUE = 0xC0
    const val CIV_DONNEES = 0x15
    const val OUVRE = 0x05
    const val FERME = 0x00

    // Audio: a 0x18-byte header, then samples. Length of the samples at 0x16 (u16 BE).
    const val AUDIO_ENTETE = 0x18
    const val A_SEQ = 0x12             // u16 BE, the radio's own audio count
    const val A_LONGUEUR = 0x16

    // ------------------------------------------------------------ bytes

    fun u16le(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    fun u16be(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    fun u32le(b: ByteArray, o: Int) = u16le(b, o) or (u16le(b, o + 2) shl 16)
    fun u32be(b: ByteArray, o: Int) = (u16be(b, o) shl 16) or u16be(b, o + 2)
    fun p16le(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
    fun p16be(b: ByteArray, o: Int, v: Int) { b[o] = (v shr 8).toByte(); b[o + 1] = v.toByte() }
    fun p32le(b: ByteArray, o: Int, v: Int) { p16le(b, o, v); p16le(b, o + 2, v ushr 16) }
    fun p32be(b: ByteArray, o: Int, v: Int) { p16be(b, o, v ushr 16); p16be(b, o + 2, v) }

    /** A zero-padded text field. */
    fun texte(b: ByteArray, o: Int, n: Int): String {
        val fin = (o until minOf(o + n, b.size)).firstOrNull { b[it].toInt() == 0 } ?: minOf(o + n, b.size)
        return String(b, o, maxOf(0, fin - o), Charsets.ISO_8859_1)
    }

    fun posTexte(b: ByteArray, o: Int, n: Int, s: String) {
        val t = s.toByteArray(Charsets.ISO_8859_1)
        for (i in 0 until n) b[o + i] = if (i < t.size) t[i] else 0
    }

    // ------------------------------------------------------------ header

    data class Entete(val longueur: Int, val type: Int, val seq: Int, val emetteur: Int, val destinataire: Int)

    fun entete(b: ByteArray): Entete? =
        if (b.size < ENTETE) null else Entete(u32le(b, 0), u16le(b, 4), u16le(b, 6), u32le(b, 8), u32le(b, 12))

    fun nouveau(longueur: Int, type: Int, seq: Int, moi: Int, lui: Int): ByteArray {
        val b = ByteArray(longueur)
        p32le(b, 0, longueur); p16le(b, 4, type); p16le(b, 6, seq and 0xFFFF); p32le(b, 8, moi); p32le(b, 12, lui)
        return b
    }

    fun controle(type: Int, seq: Int, moi: Int, lui: Int) = nouveau(ENTETE, type, seq, moi, lui)

    /** One retransmit request: the header's sequence is the packet missing. */
    fun reprise(seq: Int, moi: Int, lui: Int) = controle(T_REPRISE, seq, moi, lui)

    /**
     * The sequences a retransmit request names: the header's, and, when the
     * packet is longer, a list of u16 LE after it (a burst asked at once).
     * The list form was seen described, not captured here: kept apart so a
     * trace can correct it.
     */
    fun reprises(b: ByteArray): List<Int> {
        val e = entete(b) ?: return emptyList()
        if (b.size <= ENTETE) return listOf(e.seq)
        return (ENTETE until b.size - 1 step 2).map { u16le(b, it) }
    }

    fun ping(seq: Int, moi: Int, lui: Int, reponse: Boolean, valeur: Int): ByteArray {
        val b = nouveau(PING_LONGUEUR, T_PING, seq, moi, lui)
        b[0x10] = if (reponse) 1 else 0
        p32le(b, 0x11, valeur)
        return b
    }

    fun estReponsePing(b: ByteArray) = b.size >= PING_LONGUEUR && b[0x10].toInt() == 1
    fun valeurPing(b: ByteArray) = if (b.size >= PING_LONGUEUR) u32le(b, 0x11) else 0

    // ------------------------------------------------------------ token family

    /** The fields every token-family packet carries. */
    data class Jeton(
        val sens: Int, val genre: Int, val seqInterne: Int, val demande: Int, val jeton: Int,
        val identite: ByteArray = ByteArray(16), val erreur: Int = 0,
    )

    fun jeton(b: ByteArray): Jeton? {
        if (b.size < JETON_LONGUEUR) return null
        val e = entete(b) ?: return null
        if (e.type != T_DONNEES) return null
        // The payload size names the family: it is always the length less the header.
        if (u32be(b, J_TAILLE) != b.size - ENTETE) return null
        return Jeton(b[J_SENS].toInt() and 0xFF, b[J_GENRE].toInt() and 0xFF, u16be(b, J_SEQ_INTERNE),
            u16le(b, J_DEMANDE_JETON), u32le(b, J_JETON), b.copyOfRange(J_IDENTITE, J_IDENTITE + 16), u32le(b, J_ERREUR))
    }

    fun nouveauJeton(longueur: Int, seq: Int, moi: Int, lui: Int, j: Jeton): ByteArray {
        val b = nouveau(longueur, T_DONNEES, seq, moi, lui)
        p32be(b, J_TAILLE, longueur - ENTETE)
        b[J_SENS] = j.sens.toByte(); b[J_GENRE] = j.genre.toByte()
        p16be(b, J_SEQ_INTERNE, j.seqInterne)
        p16le(b, J_DEMANDE_JETON, j.demande)
        p32le(b, J_JETON, j.jeton)
        j.identite.copyInto(b, J_IDENTITE, 0, minOf(16, j.identite.size))
        p32le(b, J_ERREUR, j.erreur)
        return b
    }

    fun connexion(seq: Int, moi: Int, lui: Int, seqInterne: Int, demande: Int,
                  utilisateur: ByteArray, motDePasse: ByteArray, nomPc: String): ByteArray {
        val b = nouveauJeton(CONNEXION_LONGUEUR, seq, moi, lui, Jeton(SENS_DEMANDE, G_CONNEXION, seqInterne, demande, 0))
        utilisateur.copyInto(b, C_UTILISATEUR, 0, minOf(CHAMP, utilisateur.size))
        motDePasse.copyInto(b, C_MOT_DE_PASSE, 0, minOf(CHAMP, motDePasse.size))
        posTexte(b, C_NOM, CHAMP, nomPc.take(CHAMP))
        return b
    }

    /** Token create / renew / remove: the 0x40 block alone. */
    fun demandeJeton(genre: Int, seq: Int, moi: Int, lui: Int, seqInterne: Int, demande: Int, jeton: Int) =
        nouveauJeton(JETON_LONGUEUR, seq, moi, lui, Jeton(SENS_DEMANDE, genre, seqInterne, demande, jeton))

    /** One radio of the capabilities reply. */
    data class Radio(
        val identite: ByteArray, val nom: String, val audio: String, val type: Int,
        val civ: Int, val tauxRx: Int, val tauxTx: Int, val vitesse: Int,
    )

    fun capacites(b: ByteArray): List<Radio> {
        if (b.size < K_PREMIERE) return emptyList()
        val n = u16be(b, K_NOMBRE)
        return (0 until n).mapNotNull { i ->
            val o = K_PREMIERE + i * CAPACITES_ENTREE
            if (o + CAPACITES_ENTREE > b.size) null
            else Radio(b.copyOfRange(o, o + 16), texte(b, o + KE_NOM, 32), texte(b, o + KE_AUDIO, 32),
                u16be(b, o + KE_TYPE), b[o + KE_CIV].toInt() and 0xFF, u16le(b, o + KE_RX), u16le(b, o + KE_TX),
                u32be(b, o + KE_VITESSE))
        }
    }

    /** Which rates a capability bitmap offers, in Hz (bits as the descriptions give them). */
    fun tauxOfferts(bits: Int): List<Int> {
        val haut = (bits shr 8) and 0xFF
        val r = ArrayList<Int>()
        if (haut and 0x01 != 0) r += 8_000
        if (haut and 0x02 != 0) r += 16_000
        if (bits and 0x01 != 0) r += 24_000
        if (haut and 0x04 != 0) r += 32_000
        if (haut and 0x08 != 0) r += 48_000
        if (haut and 0x80 != 0) r += 12_000
        if (haut and 0x10 != 0) r += 11_025
        if (haut and 0x20 != 0) r += 22_050
        if (haut and 0x40 != 0) r += 44_100
        return r.sorted()
    }

    /** What the host asks for its streams. Receive only here: TX stays off. */
    data class Flux(
        val rxCodec: Int, val rxTaux: Int, val portCiv: Int, val portAudio: Int,
        val txActif: Boolean = false, val txCodec: Int = rxCodec, val txTaux: Int = rxTaux, val tamponTxMs: Int = 100,
    )

    fun infoHote(seq: Int, moi: Int, lui: Int, seqInterne: Int, demande: Int, jeton: Int,
                 radio: Radio, utilisateur: ByteArray, f: Flux): ByteArray {
        val b = nouveauJeton(INFO_LONGUEUR, seq, moi, lui,
            Jeton(SENS_DEMANDE, G_INFO, seqInterne, demande, jeton, radio.identite))
        posTexte(b, I_NOM, 32, radio.nom)
        utilisateur.copyInto(b, I_UTILISATEUR, 0, minOf(CHAMP, utilisateur.size))
        b[I_RX_ACTIF] = 1
        b[I_TX_ACTIF] = if (f.txActif) 1 else 0
        b[I_RX_CODEC] = f.rxCodec.toByte(); b[I_TX_CODEC] = f.txCodec.toByte()
        p32be(b, I_RX_TAUX, f.rxTaux); p32be(b, I_TX_TAUX, f.txTaux)
        p32be(b, I_PORT_CIV, f.portCiv); p32be(b, I_PORT_AUDIO, f.portAudio)
        p32be(b, I_TAMPON_TX, f.tamponTxMs)
        b[I_CONVERSION] = 1
        return b
    }

    data class InfoRadio(val nom: String, val occupe: Boolean)

    fun infoRadio(b: ByteArray): InfoRadio? =
        if (b.size < INFO_LONGUEUR) null else InfoRadio(texte(b, I_NOM, 32), b[I_OCCUPE].toInt() != 0)

    data class Statut(val portCiv: Int, val portAudio: Int, val erreur: Int)

    fun statut(b: ByteArray): Statut? =
        if (b.size < STATUT_LONGUEUR) null else Statut(u32be(b, S_PORT_CIV), u32be(b, S_PORT_AUDIO), u32le(b, J_ERREUR))

    // ------------------------------------------------------------ CI-V stream

    fun ouvreFerme(seq: Int, moi: Int, lui: Int, seqCiv: Int, ouvre: Boolean): ByteArray {
        val b = nouveau(OUVRE_LONGUEUR, T_DONNEES, seq, moi, lui)
        b[0x10] = OUVRE_MARQUE.toByte(); p16le(b, 0x11, 1)
        p16le(b, 0x13, seqCiv)
        b[0x15] = (if (ouvre) OUVRE else FERME).toByte()
        return b
    }

    fun estOuvreFerme(b: ByteArray) = b.size == OUVRE_LONGUEUR && (b[0x10].toInt() and 0xFF) == OUVRE_MARQUE

    fun civ(seq: Int, moi: Int, lui: Int, seqCiv: Int, trame: ByteArray): ByteArray {
        val b = nouveau(CIV_DONNEES + trame.size, T_DONNEES, seq, moi, lui)
        b[0x10] = CIV_MARQUE.toByte()
        p16le(b, 0x11, trame.size)
        p16le(b, 0x13, seqCiv)
        trame.copyInto(b, CIV_DONNEES)
        return b
    }

    /** The CI-V bytes a CI-V stream packet carries, or null when it is something else. */
    fun trameCiv(b: ByteArray): ByteArray? {
        if (b.size <= CIV_DONNEES || (b[0x10].toInt() and 0xFF) != CIV_MARQUE) return null
        if (entete(b)?.type != T_DONNEES) return null
        val n = u16le(b, 0x11)
        if (n <= 0 || CIV_DONNEES + n > b.size) return null
        return b.copyOfRange(CIV_DONNEES, CIV_DONNEES + n)
    }

    // ------------------------------------------------------------ audio

    data class Audio(val seq: Int, val seqAudio: Int, val donnees: ByteArray)

    fun audio(b: ByteArray): Audio? {
        if (b.size <= AUDIO_ENTETE) return null
        val e = entete(b) ?: return null
        if (e.type != T_DONNEES) return null
        val n = u16be(b, A_LONGUEUR)
        if (n <= 0 || AUDIO_ENTETE + n > b.size) return null
        return Audio(e.seq, u16be(b, A_SEQ), b.copyOfRange(AUDIO_ENTETE, AUDIO_ENTETE + n))
    }

    /** An audio packet as the radio sends it (the tests' fake radio). */
    fun nouvelAudio(seq: Int, moi: Int, lui: Int, seqAudio: Int, donnees: ByteArray, marque: Int = 0x8006): ByteArray {
        val b = nouveau(AUDIO_ENTETE + donnees.size, T_DONNEES, seq, moi, lui)
        p16be(b, 0x10, marque)
        p16be(b, A_SEQ, seqAudio)
        p16be(b, A_LONGUEUR, donnees.size)
        donnees.copyInto(b, AUDIO_ENTETE)
        return b
    }

    /** One line for the packet log: direction, length, type, sequences, ids, the first bytes past the header. */
    fun resume(sortant: Boolean, canal: String, b: ByteArray): String {
        val e = entete(b) ?: return (if (sortant) "-> " else "<- ") + canal + " court " + hex(b, 0, b.size)
        val genre = when (e.type) {
            T_DONNEES -> "données"; T_REPRISE -> "reprise"; T_ES_TU_LA -> "es-tu-là"; T_JE_SUIS_LA -> "je-suis-là"
            T_DECONNEXION -> "déconnexion"; T_PRET -> "prêt"; T_PING -> "ping"; else -> "type " + e.type
        }
        return (if (sortant) "-> " else "<- ") + canal + " " + b.size + " " + genre + " seq " + e.seq +
            " " + "%08x".format(e.emetteur) + ">" + "%08x".format(e.destinataire) +
            (if (b.size > ENTETE) " | " + hex(b, ENTETE, minOf(b.size, 0x40)) else "")
    }

    fun hex(b: ByteArray, de: Int, a: Int): String =
        (de until a).joinToString("") { "%02x".format(b[it]) }
}
