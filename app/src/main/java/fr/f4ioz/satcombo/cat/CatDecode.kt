/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.cat

/**
 * Les deux dialectes CAT, réduits à des fonctions pures.
 *
 * Tant que le découpage des trames vivait à l'intérieur des pilotes, mélangé
 * aux appels USB, il ne pouvait pas être vérifié : la seule façon de savoir si
 * une réponse était bien lue était de brancher une radio et de regarder. Sorti
 * ici, tout se contrôle au banc, avec des octets écrits à la main.
 *
 * Le bus CI-V mérite un mot, parce que c'est là qu'était le défaut le plus
 * coûteux. C'est un bus à un seul fil : ce que l'on écrit revient dans sa
 * propre oreille. Beaucoup de postes Icom ont en plus un réglage « CI-V USB
 * Echo Back » qui renvoie délibérément la question avant la réponse. Chercher
 * un octet 0x03 dans ce qui revient, comme le faisait l'ancien code, trouvait
 * donc la commande que l'on venait d'émettre, et lisait cinq octets de rien du
 * tout derrière. On croyait relire la fréquence du poste ; on relisait la
 * sienne. Il faut découper en trames, garder celles qui vont du poste vers le
 * pupitre, et alors seulement lire la charge utile.
 */
object CatDecode {

    const val PREAMBLE = 0xFE
    const val END = 0xFD
    const val ACK = 0xFB
    const val NAK = 0xFA

    // ---------------------------------------------------------------- CI-V

    /**
     * Découpe un tampon en trames CI-V complètes : `FE FE … FD`.
     *
     * Les octets qui traînent avant un préambule ou après la dernière fin de
     * trame sont jetés sans bruit — sur un bus partagé, il y en a toujours.
     */
    fun splitCiv(buf: ByteArray, n: Int = buf.size): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        val end = n.coerceAtMost(buf.size)
        while (i < end) {
            // Un préambule, c'est deux 0xFE de suite ; certains postes en
            // émettent davantage, on les avale tous.
            if ((buf[i].toInt() and 0xFF) != PREAMBLE) { i++; continue }
            var j = i
            while (j < end && (buf[j].toInt() and 0xFF) == PREAMBLE) j++
            if (j - i < 2) { i = j; continue }
            val start = j - 2
            var k = j
            while (k < end && (buf[k].toInt() and 0xFF) != END) k++
            if (k >= end) break            // trame tronquée : on la laisse
            out += buf.copyOfRange(start, k + 1)
            i = k + 1
        }
        return out
    }

    /** Cette trame va-t-elle du poste [radioAddr] vers le pupitre [ctrlAddr] ? */
    fun isFromRadio(f: ByteArray, radioAddr: Int, ctrlAddr: Int): Boolean =
        f.size >= 6 &&
            (f[2].toInt() and 0xFF) == ctrlAddr &&
            (f[3].toInt() and 0xFF) == radioAddr

    /** Cette trame est-elle notre propre question, revenue par l'écho du bus ? */
    fun isEcho(f: ByteArray, radioAddr: Int, ctrlAddr: Int): Boolean =
        f.size >= 6 &&
            (f[2].toInt() and 0xFF) == radioAddr &&
            (f[3].toInt() and 0xFF) == ctrlAddr

    /** Le code de commande d'une trame, ou -1 si elle est trop courte. */
    fun command(f: ByteArray): Int = if (f.size >= 6) f[4].toInt() and 0xFF else -1

    /**
     * La charge utile de la première réponse du poste à la commande [cmd].
     *
     * @param sub sous-commande attendue, ou -1 s'il n'y en a pas. Quand elle est
     *   donnée, elle est vérifiée et retirée de ce qui est rendu.
     * @return les octets entre la commande et le 0xFD final, ou null si aucune
     *   trame ne convient — un ACK ou un NAK ne conviennent jamais.
     */
    fun payload(
        frames: List<ByteArray>, radioAddr: Int, ctrlAddr: Int, cmd: Int, sub: Int = -1
    ): ByteArray? {
        for (f in frames) {
            if (!isFromRadio(f, radioAddr, ctrlAddr)) continue
            if (command(f) != cmd) continue
            var start = 5
            if (sub >= 0) {
                if (f.size < 7 || (f[5].toInt() and 0xFF) != sub) continue
                start = 6
            }
            if (f.size < start + 1) continue
            return f.copyOfRange(start, f.size - 1)
        }
        return null
    }

    /** Le poste a-t-il accusé réception (0xFB) ? */
    fun isAck(frames: List<ByteArray>, radioAddr: Int, ctrlAddr: Int): Boolean =
        frames.any { isFromRadio(it, radioAddr, ctrlAddr) && command(it) == ACK }

    /** Le poste a-t-il refusé (0xFA) ? */
    fun isNak(frames: List<ByteArray>, radioAddr: Int, ctrlAddr: Int): Boolean =
        frames.any { isFromRadio(it, radioAddr, ctrlAddr) && command(it) == NAK }

    // ------------------------------------------------------- BCD fréquence

    /** Une fréquence en hertz, en cinq octets BCD petit-boutiens (10 chiffres). */
    fun freqToBcdLe(hz: Long): ByteArray {
        val out = ByteArray(5)
        var d = hz
        for (i in 0 until 5) {
            val lo = (d % 10).toInt(); d /= 10
            val hi = (d % 10).toInt(); d /= 10
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /**
     * Le chemin inverse. Rend null si les octets ne sont pas du BCD valide ou
     * si la fréquence obtenue ne ressemble à rien : un demi-mégahertz ou
     * quarante gigahertz signifient qu'on a lu au mauvais endroit, et il vaut
     * mille fois mieux le dire que d'afficher un nombre.
     */
    fun bcdLeToFreq(buf: ByteArray, start: Int = 0): Long? {
        if (start + 5 > buf.size) return null
        var hz = 0L
        var mult = 1L
        for (i in 0 until 5) {
            val b = buf[start + i].toInt() and 0xFF
            val lo = b and 0x0F
            val hi = (b shr 4) and 0x0F
            if (lo > 9 || hi > 9) return null
            hz += lo * mult; mult *= 10
            hz += hi * mult; mult *= 10
        }
        return if (hz in PLAUSIBLE_HZ) hz else null
    }

    /** Bornes de vraisemblance d'une fréquence relue. */
    val PLAUSIBLE_HZ = 100_000L..30_000_000_000L

    // ------------------------------------------------------------- CTCSS

    /** Le ton le plus bas admis par un poste Icom, en dixièmes de hertz. */
    const val TONE_MIN_TENTH = 670

    /** Et le plus haut. */
    const val TONE_MAX_TENTH = 2541

    /**
     * Un ton CTCSS en trois octets BCD **gros-boutiens** : 88,5 Hz donne
     * `00 08 85`.
     *
     * C'est ici qu'était le défaut qui a coûté des passages entiers sur SO-50.
     * La fréquence, elle, est petit-boutienne, et l'ancien code a appliqué la
     * même règle au ton : 88,5 partait en `00 88 50`, que le poste relit comme
     * 885,0 Hz. Hors plage, donc ignoré — mais poliment accusé par un 0xFB, si
     * bien que l'application affichait « ton réglé » pendant que le relais
     * restait muet. Un accusé de réception ne dit pas que la commande a été
     * comprise ; il dit qu'elle a été reçue. Toute la différence est là.
     */
    fun toneToBcdBe(tenthHz: Int): ByteArray {
        val v = tenthHz.coerceIn(0, 9999)
        val s = "%04d".format(v)
        fun d(i: Int) = s[i] - '0'
        return byteArrayOf(
            0x00,
            ((d(0) shl 4) or d(1)).toByte(),
            ((d(2) shl 4) or d(3)).toByte()
        )
    }

    /** Le chemin inverse, en dixièmes de hertz. Null si ce n'est pas du BCD. */
    fun bcdBeToTone(buf: ByteArray, start: Int = 0): Int? {
        if (start + 3 > buf.size) return null
        var v = 0
        for (i in start until start + 3) {
            val b = buf[i].toInt() and 0xFF
            val hi = (b shr 4) and 0x0F
            val lo = b and 0x0F
            if (hi > 9 || lo > 9) return null
            v = v * 100 + hi * 10 + lo
        }
        return v
    }

    /** Ce ton est-il dans ce qu'un poste accepte réellement ? */
    fun toneInRange(tenthHz: Int): Boolean = tenthHz in TONE_MIN_TENTH..TONE_MAX_TENTH

    // ------------------------------------------------------------- Yaesu

    /** Les quatre octets BCD d'une fréquence FT-817, par pas de dix hertz. */
    fun yaesuFreq(hz: Long): ByteArray {
        val tenHz = (hz / 10).coerceIn(0, 99_999_999)
        val s = "%08d".format(tenHz)
        fun b(i: Int) = (((s[i] - '0') shl 4) or (s[i + 1] - '0')).toByte()
        return byteArrayOf(b(0), b(2), b(4), b(6))
    }

    /** Et l'inverse, sur les quatre premiers octets d'une réponse. */
    fun yaesuFreqOf(buf: ByteArray, start: Int = 0): Long? {
        if (start + 4 > buf.size) return null
        var hz = 0L
        for (i in start until start + 4) {
            val b = buf[i].toInt() and 0xFF
            val hi = (b shr 4) and 0x0F
            val lo = b and 0x0F
            if (hi > 9 || lo > 9) return null
            hz = hz * 100 + hi * 10 + lo
        }
        return hz * 10
    }

    // ------------------------------------------------- traductions humaines

    private fun mhz(hz: Long): String = "%.5f MHz".format(hz / 1e6)

    /**
     * Le nom du mode, tel qu'on l'écrit sur la face avant du poste.
     *
     * Public depuis la 18.19 : ce n'est plus seulement une commodité de
     * journal, c'est ce qu'affiche le panneau POSTE. USB ou LSB décide de quel
     * côté du transpondeur on se trouve, et se tromper de bande latérale c'est
     * s'entendre à l'envers ou pas du tout — un défaut qui ne se voit pas,
     * puisque tout le reste est juste.
     */
    fun civModeName(b: Int): String = when (b) {
        0x00 -> "LSB"; 0x01 -> "USB"; 0x02 -> "AM"; 0x03 -> "CW"
        0x05 -> "FM"; 0x07 -> "CW-R"; else -> "mode %02X".format(b)
    }

    /**
     * Une trame CI-V en français, pour le journal.
     *
     * Le but n'est pas l'exhaustivité : c'est de rendre lisible, sans manuel
     * ouvert à côté, la poignée de commandes que l'application émet vraiment.
     */
    fun describeCiv(f: ByteArray): String {
        if (f.size < 6) return "trame incomplète"
        val cmd = command(f)
        val d = f.copyOfRange(5, f.size - 1)
        fun sub(i: Int) = if (d.size > i) d[i].toInt() and 0xFF else -1
        return when (cmd) {
            ACK -> "accusé de réception"
            NAK -> "refus"
            0x03 -> bcdLeToFreq(d)?.let { "fréquence : ${mhz(it)}" } ?: "lecture de la fréquence"
            0x04 -> "lecture du mode"
            0x05 -> bcdLeToFreq(d)?.let { "fréquence ← ${mhz(it)}" } ?: "réglage de fréquence"
            0x06 -> if (d.isEmpty()) "réglage du mode" else "mode ← ${civModeName(sub(0))}"
            0x07 -> when (sub(0)) {
                0x00 -> "VFO A"
                0x01 -> "VFO B"
                0xD0 -> "bande principale (descente)"
                0xD1 -> "bande secondaire (montée)"
                else -> "choix du VFO"
            }
            0x0F -> when (sub(0)) {
                0x00 -> "split coupé"; 0x01 -> "split activé"; else -> "état du split"
            }
            0x16 -> when (sub(0)) {
                0x5A -> if (sub(1) == 1) "mode satellite activé" else "mode satellite coupé"
                0x42 -> if (sub(1) == 1) "ton d'accès activé" else "ton d'accès coupé"
                else -> "réglage %02X".format(sub(0))
            }
            0x1B -> if (d.size >= 4) {
                val t = bcdBeToTone(d, 1)
                if (t != null) "ton d'accès ← %.1f Hz".format(t / 10.0) else "ton d'accès"
            } else "lecture du ton d'accès"
            0x25 -> {
                val which = if (sub(0) == 0x01) "VFO non sélectionné" else "VFO sélectionné"
                val fq = if (d.size >= 6) bcdLeToFreq(d, 1) else null
                if (fq != null) "$which ← ${mhz(fq)}" else "$which : fréquence"
            }
            0x26 -> {
                val which = if (sub(0) == 0x01) "VFO non sélectionné" else "VFO sélectionné"
                if (d.size >= 2) "$which ← ${civModeName(sub(1))}" else "$which : mode"
            }
            else -> "commande %02X".format(cmd)
        }
    }

    private fun yaesuMode(b: Int): String = when (b) {
        0x00 -> "LSB"; 0x01 -> "USB"; 0x02 -> "CW"; 0x03 -> "CW-R"
        0x04 -> "AM"; 0x08 -> "FM"; 0x0A -> "DIG"; 0x0C -> "PKT"
        else -> "mode %02X".format(b)
    }

    /**
     * Une trame Yaesu en français. Le protocole du FT-817 n'a ni adresse ni
     * délimiteur : c'est cinq octets à l'aller, et une réponse dont la longueur
     * dépend de la question posée. [fromRig] dit donc de quel côté on est, et
     * [lastOp] rappelle la question quand on décrit la réponse.
     */
    fun describeYaesu(f: ByteArray, fromRig: Boolean, lastOp: Int = -1): String {
        if (!fromRig) {
            if (f.size < 5) return "trame incomplète"
            return when (f[4].toInt() and 0xFF) {
                0x00 -> "PTT fermé"
                0x01 -> yaesuFreqOf(f)?.let { "fréquence ← ${mhz(it)}" } ?: "réglage de fréquence"
                0x03 -> "lecture fréquence et mode"
                0x07 -> "mode ← ${yaesuMode(f[0].toInt() and 0xFF)}"
                0x0A -> when (f[0].toInt() and 0xFF) {
                    0x8A -> "ton d'accès coupé"
                    0x4A -> "ton d'accès en émission"
                    0x2A -> "ton d'accès en émission et réception"
                    else -> "réglage du ton"
                }
                0x0B -> {
                    val t = ((f[0].toInt() shr 4 and 0x0F) * 1000 + (f[0].toInt() and 0x0F) * 100 +
                        (f[1].toInt() shr 4 and 0x0F) * 10 + (f[1].toInt() and 0x0F))
                    "ton d'accès ← %.1f Hz".format(t / 10.0)
                }
                0x81 -> "PTT ouvert"
                0xF7 -> "lecture de l'état d'émission"
                else -> "commande %02X".format(f[4].toInt() and 0xFF)
            }
        }
        return when {
            lastOp == 0xF7 && f.size >= 1 ->
                if ((f[0].toInt() and 0x80) == 0) "le poste émet" else "le poste reçoit"
            f.size >= 5 -> {
                val fq = yaesuFreqOf(f)
                if (fq != null) "fréquence : ${mhz(fq)}, ${yaesuMode(f[4].toInt() and 0xFF)}"
                else "réponse de cinq octets"
            }
            f.size == 1 -> if (f[0].toInt() and 0xFF == 0x00) "accusé de réception" else
                "réponse d'un octet"
            else -> "réponse de ${f.size} octets"
        }
    }
}
