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
 * Deux postes qui n'existent pas, et qui sont pourtant plus utiles que les vrais.
 *
 * L'intérêt d'un poste simulé n'est pas de dire oui. Un poste qui dit toujours
 * oui ne prouve rien : c'est exactement le comportement qui a laissé passer
 * pendant des mois un ton d'accès mal encodé, poliment accusé et jamais
 * appliqué. Ceux-ci refusent ce que le vrai refuse — un ton hors plage, une
 * commande interdite sur la bande secondaire en mode satellite — et ils
 * comptent leurs refus. Un essai peut alors exiger qu'une séquence saine n'en
 * produise aucun, ce qui est une affirmation autrement plus forte que « ça n'a
 * pas planté ».
 */

/**
 * Un IC-9700 en mémoire : deux bandes, un mode satellite, un split, des VFO.
 *
 * Il sait aussi renvoyer l'écho de ce qu'on lui écrit — c'est le réglage
 * « CI-V USB Echo Back » des vrais postes, et c'est la panne qu'il faut pouvoir
 * reproduire à volonté, puisque c'est elle qui faisait relire au pilote sa
 * propre question.
 */
class Ic9700Sim(
    val radioAddr: Int = 0xA2,
    val ctrlAddr: Int = 0xE0
) : SerialLink {

    var satMode: Boolean = false; private set
    var split: Boolean = false; private set
    /** Descente : la bande principale. */
    var mainHz: Long = 435_000_000L; private set
    /** Montée : la bande secondaire. */
    var subHz: Long = 145_000_000L; private set
    /** Vrai quand la bande secondaire est celle qui est sélectionnée. */
    var onSub: Boolean = false; private set
    var mainMode: Int = 0x01; private set
    var subMode: Int = 0x01; private set
    var toneOn: Boolean = false; private set
    var toneTenthHz: Int = 0; private set

    /** Le nombre de commandes que le poste a refusées depuis le début. */
    var refusals: Int = 0; private set

    /**
     * La règle du vrai poste : **jamais les deux bandes sur la même à la fois**.
     *
     * « Attention, on ne peut pas être sur la même bande en même temps sur VFO A
     * et B. » L'ancien simulateur acceptait tout, et c'est pour cela qu'il n'a
     * rien vu venir : la séquence qui échouait sur le bureau d'Olivier passait
     * ici sans un refus. Le poste, lui, répond NAK — en silence, puisque rien
     * n'affichait ce refus — et la bande ne change pas.
     */
    var bandExclusive: Boolean = true

    /** Vrai si poser [hz] sur cette bande-là mettrait les deux ensemble. */
    private fun collision(surSub: Boolean, hz: Long): Boolean {
        if (!bandExclusive) return false
        val b = BandPlan.band(hz)
        if (b == BandPlan.Band.AUTRE) return false
        return b == BandPlan.band(if (surSub) mainHz else subHz)
    }

    /** Renvoyer la question avant la réponse, comme le fait « CI-V Echo Back ». */
    var echo: Boolean = false

    /**
     * Faire précéder chaque réponse d'un accusé de réception.
     *
     * Certains postes le font, et c'est ce qui tronquait la lecture unique de
     * l'ancien pilote : il rendait l'accusé, et la vraie réponse se perdait.
     */
    var ackBeforeReply: Boolean = false

    /** Tout ce que le poste a reçu, trame par trame — pour les essais. */
    val received = ArrayList<ByteArray>()

    private val outbox = ArrayDeque<Byte>()
    private val inbox = ArrayList<Byte>()
    private var closed = false

    override fun write(bytes: ByteArray, timeoutMs: Int): Boolean {
        if (closed) return false
        bytes.forEach { inbox += it }
        // On ne traite que les trames complètes ; le reste attend la suite.
        val buf = inbox.toByteArray()
        val frames = CatDecode.splitCiv(buf)
        if (frames.isNotEmpty()) {
            val consumed = frames.sumOf { it.size }
            // Approximation volontaire : nos essais n'émettent pas de bruit
            // entre les trames, donc ce qui reste est bien une trame partielle.
            val lastEnd = buf.indexOfLast { (it.toInt() and 0xFF) == CatDecode.END } + 1
            val keep = if (lastEnd in 1..buf.size) buf.copyOfRange(lastEnd, buf.size) else ByteArray(0)
            inbox.clear(); keep.forEach { inbox += it }
            if (consumed > 0) frames.forEach { handle(it) }
        }
        return true
    }

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        var n = 0
        while (n < buf.size && outbox.isNotEmpty()) { buf[n++] = outbox.removeFirst() }
        return n
    }

    override fun close() { closed = true; outbox.clear(); inbox.clear() }

    val isClosed: Boolean get() = closed

    private fun emit(f: ByteArray) { f.forEach { outbox.addLast(it) } }

    private fun frameToCtrl(cmd: Int, data: ByteArray = ByteArray(0)): ByteArray =
        byteArrayOf(0xFE.toByte(), 0xFE.toByte(), ctrlAddr.toByte(), radioAddr.toByte(),
            cmd.toByte()) + data + byteArrayOf(CatDecode.END.toByte())

    private fun ack() = emit(frameToCtrl(CatDecode.ACK))

    /** Une réponse, précédée s'il le faut d'un accusé de réception. */
    private fun reply(cmd: Int, data: ByteArray) {
        if (ackBeforeReply) emit(frameToCtrl(CatDecode.ACK))
        emit(frameToCtrl(cmd, data))
    }
    private fun nak() { refusals++; emit(frameToCtrl(CatDecode.NAK)) }

    private fun handle(f: ByteArray) {
        received += f
        // Une trame qui ne nous est pas adressée n'est pas la nôtre : sur un bus
        // partagé, c'est la règle, et c'est aussi le cas de notre propre écho.
        if (f.size < 6) return
        val to = f[2].toInt() and 0xFF
        if (to != radioAddr) return
        if (echo) emit(f)

        val cmd = f[4].toInt() and 0xFF
        val d = f.copyOfRange(5, f.size - 1)
        fun at(i: Int) = if (d.size > i) d[i].toInt() and 0xFF else -1

        when (cmd) {
            0x03 -> reply(0x03, CatDecode.freqToBcdLe(if (onSub) subHz else mainHz))
            0x04 -> reply(0x04, byteArrayOf((if (onSub) subMode else mainMode).toByte(), 0x01))
            0x05 -> {
                val hz = CatDecode.bcdLeToFreq(d)
                if (hz == null || collision(onSub, hz)) nak() else {
                    if (onSub) subHz = hz else mainHz = hz
                    ack()
                }
            }
            0x06 -> {
                if (d.isEmpty()) nak() else {
                    if (onSub) subMode = at(0) else mainMode = at(0)
                    ack()
                }
            }
            0x07 -> when (at(0)) {
                0x00 -> { onSub = false; ack() }
                0x01 -> { onSub = true; ack() }
                0xD0 -> { onSub = false; ack() }
                0xD1 -> { onSub = true; ack() }
                else -> nak()
            }
            0x0F -> when (at(0)) {
                0x00 -> { split = false; ack() }
                0x01 -> { split = true; ack() }
                else -> nak()
            }
            0x16 -> when (at(0)) {
                0x5A -> { satMode = at(1) == 1; ack() }
                0x42 -> { toneOn = at(1) == 1; ack() }
                else -> nak()
            }
            0x1B -> {
                if (at(0) != 0x00) { nak(); return }
                if (d.size < 4) {
                    reply(0x1B, byteArrayOf(0x00) + CatDecode.toneToBcdBe(toneTenthHz))
                    return
                }
                val t = CatDecode.bcdBeToTone(d, 1)
                // Et voilà le refus qui manquait. L'ancien encodeur envoyait
                // 88,5 Hz sous la forme 00 88 50, soit 885,0 Hz : hors plage.
                // Le vrai poste le refuse ; celui-ci aussi, et il le compte.
                if (t == null || !CatDecode.toneInRange(t)) nak() else { toneTenthHz = t; ack() }
            }
            0x25, 0x26 -> {
                // Sur un IC-9700 en mode satellite transbande, ces deux
                // commandes ne savent pas atteindre la bande secondaire. Elles
                // sont refusées, et non silencieusement appliquées à la
                // principale — ce qui aurait été bien pire.
                val unselected = at(0) == 0x01
                if (satMode && unselected) { nak(); return }
                if (cmd == 0x25) {
                    if (d.size < 6) {
                        reply(0x25, byteArrayOf(d.getOrElse(0) { 0 }) +
                            CatDecode.freqToBcdLe(if (unselected) subHz else mainHz))
                        return
                    }
                    val hz = CatDecode.bcdLeToFreq(d, 1)
                    if (hz == null || collision(unselected, hz)) nak() else {
                        if (unselected) subHz = hz else mainHz = hz
                        ack()
                    }
                } else {
                    if (d.size < 2) { nak(); return }
                    if (unselected) subMode = at(1) else mainMode = at(1)
                    ack()
                }
            }
            else -> nak()
        }
    }
}

/**
 * Un FT-817 en mémoire : une seule fréquence, un mode, un ton, et ce bit
 * d'état inversé qui a piégé tout le monde au moins une fois.
 *
 * Le protocole Yaesu n'a ni adresse ni délimiteur : cinq octets à l'aller,
 * toujours, et une réponse dont la longueur dépend de la question. Il n'y a
 * donc rien à découper, mais tout à compter — un octet de trop et l'on décale
 * tout ce qui suit.
 */
class Ft817Sim : SerialLink {

    var hz: Long = 145_800_000L; private set
    var mode: Int = 0x01; private set
    var toneTenthHz: Int = 0; private set
    var toneMode: Int = 0x8A; private set
    /** Vrai quand le poste émet. Le bit d'état, lui, est mis en **réception**. */
    var transmitting: Boolean = false

    /** Commandes que le poste n'a pas comprises et laissées sans réponse. */
    var refusals: Int = 0; private set

    val received = ArrayList<ByteArray>()

    private val outbox = ArrayDeque<Byte>()
    private val inbox = ArrayList<Byte>()
    private var closed = false

    override fun write(bytes: ByteArray, timeoutMs: Int): Boolean {
        if (closed) return false
        bytes.forEach { inbox += it }
        while (inbox.size >= 5) {
            val f = ByteArray(5) { inbox[it] }
            repeat(5) { inbox.removeAt(0) }
            handle(f)
        }
        return true
    }

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        var n = 0
        while (n < buf.size && outbox.isNotEmpty()) { buf[n++] = outbox.removeFirst() }
        return n
    }

    override fun close() { closed = true; outbox.clear(); inbox.clear() }

    val isClosed: Boolean get() = closed

    private fun emit(vararg b: Byte) { b.forEach { outbox.addLast(it) } }

    private fun handle(f: ByteArray) {
        received += f
        fun p(i: Int) = f[i].toInt() and 0xFF
        when (p(4)) {
            0x00 -> { transmitting = true; emit(0x00) }
            0x81 -> { transmitting = false; emit(0x00) }
            0x01 -> {
                val v = CatDecode.yaesuFreqOf(f)
                if (v == null) { refusals++; return }
                hz = v; emit(0x00)
            }
            0x03 -> {
                val b = CatDecode.yaesuFreq(hz)
                emit(b[0], b[1], b[2], b[3], mode.toByte())
            }
            0x07 -> { mode = p(0); emit(0x00) }
            0x0A -> {
                if (p(0) != 0x8A && p(0) != 0x4A && p(0) != 0x2A) { refusals++; return }
                toneMode = p(0); emit(0x00)
            }
            0x0B -> {
                val t = (p(0) shr 4) * 1000 + (p(0) and 0x0F) * 100 +
                    (p(1) shr 4) * 10 + (p(1) and 0x0F)
                if (!CatDecode.toneInRange(t)) { refusals++; return }
                toneTenthHz = t; emit(0x00)
            }
            0xF7 -> {
                // Le bit 7 est MIS en réception et effacé en émission. C'est
                // contre-intuitif, c'est le manuel, et l'inverser revient à
                // écrire sur le VFO du poste pendant qu'il émet.
                val b = if (transmitting) 0x00 else 0x80
                emit(b.toByte())
            }
            else -> refusals++
        }
    }
}
