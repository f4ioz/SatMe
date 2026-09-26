/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.cat

import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Un fil série, et rien d'autre : écrire, lire, fermer.
 *
 * C'est la pièce qui manquait, et son absence a coûté cher. Les deux pilotes
 * CAT tenaient chacun leur `UsbSerialPort` ; aucun essai ne pouvait donc
 * exister sans radio, sans câble et sans opérateur. La seule vérification
 * possible était de brancher, de regarder la face avant, et de conclure que la
 * fréquence avait bougé, donc que c'était bon. Ce raisonnement a un trou, et il
 * est large : une radio qui accuse réception d'une commande qu'elle ignore
 * ensuite ressemble en tout point, vue de l'extérieur, à une radio qui obéit.
 */
interface SerialLink {
    /** Écrit [bytes]. Rend vrai si l'écriture est partie. */
    fun write(bytes: ByteArray, timeoutMs: Int = 500): Boolean

    /** Lit au plus [buf].size octets. Rend le nombre lu, zéro si rien. */
    fun read(buf: ByteArray, timeoutMs: Int = 300): Int

    fun close()
}

/** Le fil série réel, celui du câble USB. */
class UsbSerialLink(private val port: UsbSerialPort) : SerialLink {
    override fun write(bytes: ByteArray, timeoutMs: Int): Boolean =
        runCatching { port.write(bytes, timeoutMs) }.isSuccess

    override fun read(buf: ByteArray, timeoutMs: Int): Int =
        runCatching { port.read(buf, timeoutMs) }.getOrDefault(0)

    override fun close() { runCatching { port.close() } }
}

/**
 * Le journal des trames : les deux cents dernières, dans les deux sens.
 *
 * Il ne sert pas à faire joli. Quand une vraie radio boude, la seule question
 * utile est « la trame est-elle partie, et qu'a répondu le poste ? » — et
 * jusqu'ici rien dans l'application ne pouvait y répondre.
 */
object CatJournal {

    data class Entry(
        val tMs: Long,
        /** Vrai pour une trame émise vers le poste, faux pour une réponse. */
        val out: Boolean,
        val hex: String,
        /** La même chose en français, pour ceux qui ne lisent pas l'hexadécimal. */
        val text: String
    )

    const val DEPTH = 200

    /** Journalisation active. Coupée par défaut : elle coûte une allocation par trame. */
    @Volatile var enabled: Boolean = false

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    fun log(out: Boolean, bytes: ByteArray, text: String, tMs: Long = System.currentTimeMillis()) {
        if (!enabled) return
        val e = Entry(tMs, out, bytes.joinToString(" ") { "%02X".format(it) }, text)
        val cur = _entries.value
        _entries.value = (if (cur.size >= DEPTH) cur.drop(cur.size - DEPTH + 1) else cur) + e
    }

    fun clear() { _entries.value = emptyList() }
}
