/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.domain

/**
 * Le codage des messages FT8, sans la partie radio.
 *
 * FT8 envoie **79 symboles** de 8-FSK, à 6,25 bauds et 6,25 Hz d'écartement :
 * 12,64 secondes de transmission dans une fenêtre de quinze. Trois groupes de
 * sept symboles — les réseaux de Costas — servent de repères de synchronisation
 * au début, au milieu et à la fin. Restent 58 symboles utiles, soit 174 bits.
 *
 * Ces 174 bits sont un mot de code correcteur : 77 bits de message, 14 bits de
 * contrôle, 83 bits de parité. **Ce fichier ignore les 83 bits de parité.**
 *
 * C'est un choix, et il faut le comprendre. Le décodage complet emploie un
 * code LDPC (174,91) dont les tables font plusieurs centaines de valeurs ; mal
 * recopiées, elles ne corrigent rien tout en donnant l'illusion de travailler.
 * On s'en passe donc, et l'on s'appuie sur le CRC-14 : si les 91 premiers bits
 * sont lus sans erreur, le contrôle tombe juste et le message est vrai ; si un
 * seul bit est faux, le contrôle échoue et l'on n'affiche rien.
 *
 * La conséquence est nette et assumée : **les signaux forts se décodent, les
 * faibles ne se décodent pas**. Sur QO-100, où le rapport signal sur bruit
 * dépasse couramment vingt décibels, la plupart des stations passent. En
 * troposphérique marginal, presque aucune. Un décodeur qui rate n'a jamais
 * trompé personne ; un décodeur qui invente, si.
 */
object Ft8 {

    /** Sept symboles de repère, au début, au milieu et à la fin. */
    val COSTAS = intArrayOf(3, 1, 4, 0, 6, 5, 2)

    const val SYMBOLES = 79
    const val SYMBOLES_DONNEES = 58
    const val BITS = 174
    /** Message utile + contrôle, avant les bits de parité. */
    const val BITS_UTILES = 91
    const val BITS_MESSAGE = 77

    /** 6,25 Hz d'écartement, 6,25 bauds : la durée d'un symbole vaut 0,16 s. */
    const val ECART_HZ = 6.25
    const val DUREE_SYMBOLE_S = 0.16
    /** Une transmission dure 12,64 s dans une fenêtre de 15. */
    const val DUREE_S = SYMBOLES * DUREE_SYMBOLE_S

    /**
     * Le code de Gray, dans le sens ton → valeur.
     *
     * FT8 range les tons de sorte que deux tons voisins ne diffèrent que d'un
     * bit : une erreur d'un demi-écartement ne fausse alors qu'un bit sur
     * trois, au lieu de trois sur trois. Sans code correcteur c'est ce qui
     * sauve le plus de messages.
     */
    private val GRAY = intArrayOf(0, 1, 3, 2, 5, 6, 4, 7)
    private val GRAY_INVERSE = IntArray(8).also { inv ->
        GRAY.forEachIndexed { valeur, ton -> inv[ton] = valeur }
    }

    /** Les 58 symboles de données deviennent 174 bits. */
    fun symbolesVersBits(tons: IntArray): BooleanArray {
        val bits = BooleanArray(BITS)
        var i = 0
        donnees(tons).forEach { ton ->
            val v = GRAY_INVERSE[ton and 7]
            bits[i++] = (v shr 2) and 1 == 1
            bits[i++] = (v shr 1) and 1 == 1
            bits[i++] = v and 1 == 1
        }
        return bits
    }

    /** Les 79 symboles reçus, débarrassés des trois réseaux de Costas. */
    fun donnees(tons: IntArray): IntArray {
        require(tons.size == SYMBOLES) { "il faut $SYMBOLES symboles" }
        val out = IntArray(SYMBOLES_DONNEES)
        var j = 0
        for (i in 0 until SYMBOLES) {
            val repere = i < 7 || i in 36..42 || i >= 72
            if (!repere) out[j++] = tons[i]
        }
        return out
    }

    /** Les 174 bits redeviennent 79 symboles, Costas compris. */
    fun bitsVersSymboles(bits: BooleanArray): IntArray {
        require(bits.size == BITS) { "il faut $BITS bits" }
        val tons = IntArray(SYMBOLES)
        var b = 0
        var j = 0
        for (i in 0 until SYMBOLES) {
            tons[i] = when {
                i < 7 -> COSTAS[i]
                i in 36..42 -> COSTAS[i - 36]
                i >= 72 -> COSTAS[i - 72]
                else -> {
                    val v = (if (bits[b]) 4 else 0) or
                        (if (bits[b + 1]) 2 else 0) or
                        (if (bits[b + 2]) 1 else 0)
                    b += 3
                    j++
                    GRAY[v]
                }
            }
        }
        return tons
    }

    /**
     * Combien de symboles de Costas tombent juste, sur les vingt et un.
     *
     * C'est le critère de synchronisation : on essaie chaque instant et chaque
     * fréquence plausibles, et l'on retient les candidats dont les repères
     * concordent. Vingt et un sur vingt et un est le cas idéal ; on accepte
     * plus bas, quitte à ce que le CRC écarte ensuite.
     */
    fun scoreCostas(tons: IntArray): Int {
        require(tons.size == SYMBOLES) { "il faut $SYMBOLES symboles" }
        var bons = 0
        for (i in 0 until 7) {
            if (tons[i] == COSTAS[i]) bons++
            if (tons[36 + i] == COSTAS[i]) bons++
            if (tons[72 + i] == COSTAS[i]) bons++
        }
        return bons
    }

    // ------------------------------------------------------------------ CRC

    /**
     * Le contrôle sur quatorze bits, polynôme 0x2757.
     *
     * **C'est lui qui remplace le code correcteur.** Il porte sur les 77 bits
     * du message suivis de cinq zéros, et sa probabilité de tomber juste par
     * hasard est de une sur seize mille : un message affiché est un message
     * vrai, à cela près.
     */
    fun crc14(message: BooleanArray): Int {
        require(message.size >= BITS_MESSAGE) { "message trop court" }
        var reg = 0
        // 77 bits de message, puis cinq zéros : 82 bits en tout.
        for (i in 0 until BITS_MESSAGE + 5) {
            val bit = if (i < BITS_MESSAGE && message[i]) 1 else 0
            reg = reg shl 1
            if (((reg shr 14) and 1) xor bit == 1) reg = reg xor 0x2757
            reg = reg and 0x3FFF
        }
        return reg
    }

    /** Les 91 bits utiles portent-ils un contrôle cohérent ? */
    fun controleJuste(utiles: BooleanArray): Boolean {
        if (utiles.size < BITS_UTILES) return false
        var lu = 0
        for (i in BITS_MESSAGE until BITS_UTILES) {
            lu = (lu shl 1) or (if (utiles[i]) 1 else 0)
        }
        return lu == crc14(utiles)
    }

    /** Pose le contrôle derrière le message : l'inverse de [controleJuste]. */
    fun avecControle(message: BooleanArray): BooleanArray {
        val out = BooleanArray(BITS_UTILES)
        message.copyInto(out, 0, 0, BITS_MESSAGE)
        val c = crc14(message)
        for (i in 0 until 14) {
            out[BITS_MESSAGE + i] = (c shr (13 - i)) and 1 == 1
        }
        return out
    }

    // ------------------------------------------------------- les indicatifs

    private const val A1 = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val A2 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val A3 = "0123456789"
    private const val A4 = " ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    /** Au-dessous, ce sont des jetons — CQ, DE, QRZ — et non des indicatifs. */
    private const val JETONS = 2_063_592L
    private const val HACHES = 4_194_304L

    /**
     * Un indicatif standard, rangé sur vingt-huit bits.
     *
     * Le codage n'est pas alphabétique : il découpe l'indicatif en six cases
     * de natures différentes — lettre ou chiffre, chiffre seul, lettres — et
     * les combine en base mixte. C'est ce qui permet de tenir un indicatif
     * dans moins de quatre octets.
     *
     * Rend `null` pour ce qui n'entre pas dans ce moule : indicatifs composés,
     * préfixes, suffixes. Ceux-là voyagent hachés sur vingt-deux bits, et l'on
     * ne peut pas les retrouver sans avoir déjà entendu l'indicatif en clair —
     * raison pour laquelle on n'affiche rien plutôt qu'une approximation.
     */
    fun indicatifDepuis28(n: Long): String? {
        if (n < JETONS + HACHES) return null
        var v = n - JETONS - HACHES
        val c = CharArray(6)
        c[5] = A4[(v % 27).toInt()]; v /= 27
        c[4] = A4[(v % 27).toInt()]; v /= 27
        c[3] = A4[(v % 27).toInt()]; v /= 27
        c[2] = A3[(v % 10).toInt()]; v /= 10
        c[1] = A2[(v % 36).toInt()]; v /= 36
        c[0] = A1[(v % 37).toInt()]
        return String(c).trim().ifBlank { null }
    }

    /** L'inverse : un indicatif standard vers ses vingt-huit bits. */
    fun indicatifVers28(indicatif: String): Long? {
        val s = cadre(indicatif.trim().uppercase()) ?: return null
        val i1 = A1.indexOf(s[0]); val i2 = A2.indexOf(s[1])
        val i3 = A3.indexOf(s[2]); val i4 = A4.indexOf(s[3])
        val i5 = A4.indexOf(s[4]); val i6 = A4.indexOf(s[5])
        if (i1 < 0 || i2 < 0 || i3 < 0 || i4 < 0 || i5 < 0 || i6 < 0) return null
        var v = i1.toLong()
        v = v * 36 + i2; v = v * 10 + i3
        v = v * 27 + i4; v = v * 27 + i5; v = v * 27 + i6
        return v + JETONS + HACHES
    }

    /**
     * Aligne un indicatif sur les six cases du moule.
     *
     * Le chiffre doit tomber en troisième position. « F4IOZ » y va tel quel une
     * fois complété ; « G0ABC » aussi ; « 2E0XYZ » également. Un indicatif dont
     * le chiffre n'est ni en deuxième ni en troisième place n'entre pas dans le
     * moule, et c'est le cas de tous les composés.
     */
    private fun cadre(s: String): String? {
        if (s.length !in 3..6) return null
        val pos = s.indexOfFirst { it.isDigit() }
        return when (pos) {
            1 -> " " + s.padEnd(5, ' ')
            2 -> s.padEnd(6, ' ')
            else -> null
        }.takeIf { it?.length == 6 }
    }

    // ------------------------------------------------------------ le message

    /** Ce qu'un message décodé contient, une fois déplié. */
    data class Message(
        val brut: String,
        val appelant: String?,
        val appele: String?,
        val locator: String?,
        val rapportDb: Int?,
    )

    /**
     * Déplie les 77 bits d'un message ordinaire.
     *
     * On ne traite que le **type 1**, celui des contacts courants : deux
     * indicatifs standard et un carré ou un rapport. Les autres types — appels
     * de concours, messages libres, indicatifs composés — sont reconnus et
     * écartés plutôt que devinés. Afficher un indicatif approché serait pire
     * que de n'afficher personne : il finirait dans un carnet.
     */
    fun deplie(message: BooleanArray): Message? {
        if (message.size < BITS_MESSAGE) return null
        val i3 = lisEntier(message, 74, 3).toInt()
        if (i3 != 1) return null

        val c1 = lisEntier(message, 0, 28)
        val c2 = lisEntier(message, 29, 28)
        // Bit 59 et non 58. La disposition du type 1 est
        // c28 r1 c28 r1 R1 g15 i3 : 0-27, 28, 29-56, 57, **58**, 59-73, 74-76.
        // Lu un bit trop tôt, le champ avalait le bit « roger » qui le précède
        // et rendait très exactement la moitié de la vraie valeur — KO02, qui
        // vaut 19402, sortait en FH01, qui vaut 9701. Le défaut ne s'est vu
        // qu'en décodant de vraies stations : le banc ne l'a pas attrapé parce
        // qu'il écrivait et relisait au même mauvais endroit.
        val g15 = lisEntier(message, 59, 15).toInt()

        val appele = jetonOuIndicatif(c1)
        val appelant = jetonOuIndicatif(c2)
        if (appele == null || appelant == null) return null

        var carre: String? = null
        var rapport: Int? = null
        var accuse: String? = null
        if (g15 < 32_400) {
            // Un carré à quatre caractères, rangé en base mixte.
            val j = g15
            carre = "" + ('A' + j / (10 * 10 * 18)) +
                ('A' + (j / (10 * 10)) % 18) +
                ('0' + (j / 10) % 10) + ('0' + j % 10)
        } else {
            // Au-delà des carrés, le champ porte les accusés de réception et
            // les rapports. Tout se compte à partir de 32 400, et c'est ce
            // retranchement qui manquait : « g15 − 35 » affichait +32 367 là
            // où il fallait lire −33.
            when (val code = g15 - 32_400) {
                1 -> Unit                       // rien : ni carré, ni rapport
                2 -> accuse = "RRR"
                3 -> accuse = "RR73"
                4 -> accuse = "73"
                else -> if (code >= 5) rapport = code - 35
            }
        }

        val bout = carre ?: accuse
            ?: rapport?.let { (if (it >= 0) "+" else "") + it } ?: ""
        return Message(
            brut = listOf(appele, appelant, bout).filter { it.isNotBlank() }
                .joinToString(" "),
            appelant = appelant.takeIf { it != "CQ" && it != "QRZ" && it != "DE" },
            appele = appele.takeIf { it != "CQ" && it != "QRZ" && it != "DE" },
            locator = carre,
            rapportDb = rapport)
    }

    /** Les trois jetons réservés, puis les indicatifs proprement dits. */
    private fun jetonOuIndicatif(n: Long): String? = when (n) {
        0L -> "DE"
        1L -> "QRZ"
        2L -> "CQ"
        else -> indicatifDepuis28(n)
    }

    private fun lisEntier(bits: BooleanArray, debut: Int, longueur: Int): Long {
        var v = 0L
        for (i in 0 until longueur) {
            v = (v shl 1) or (if (bits[debut + i]) 1L else 0L)
        }
        return v
    }

    /** Range un entier sur `longueur` bits, pour le banc d'essai. */
    fun ecritEntier(bits: BooleanArray, debut: Int, longueur: Int, valeur: Long) {
        for (i in 0 until longueur) {
            bits[debut + i] = (valeur shr (longueur - 1 - i)) and 1L == 1L
        }
    }
}
