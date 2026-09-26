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
 * Le codage des messages FT4.
 *
 * FT4 partage avec FT8 tout le haut de la pile — les mêmes 77 bits de message,
 * le même contrôle sur quatorze bits, le même code correcteur (174, 91) — et
 * ne diffère que par la couche physique : quatre tons au lieu de huit, trois
 * fois plus rapide, et une synchronisation par quatre réseaux de Costas au
 * lieu de trois.
 *
 * **Sur la provenance.** Tout ce fichier vient de la description du protocole
 * publiée par Steve Franke K9AN, Bill Somerville G4WJS et Joe Taylor K1JT dans
 * QEX de juillet-août 2020. Les auteurs y placent explicitement cette
 * description **dans le domaine public** ; seul le code source de WSJT-X reste
 * sous GPL, et rien n'en est repris ici. Les valeurs ci-dessous sont recopiées
 * de l'article, pas du logiciel.
 *
 * **Les conditions qui vont avec**, et qui ne sont pas négociables si l'on veut
 * employer le nom « FT4 » :
 *
 * - respecter la définition du protocole — codage de source, correction
 *   d'erreurs, modulation ;
 * - **interdire explicitement les QSO robotisés ou sans opérateur** ;
 * - ne pas s'attribuer les types de message encore non assignés.
 *
 * La deuxième est une obligation de conception, pas une note de bas de page :
 * un automate qui répondrait tout seul à un appel sortirait SatMe du droit
 * d'employer ce nom.
 */
object Ft4 {

    /**
     * Les quatre réseaux de Costas, placés aux quatre coins de la trame.
     *
     * Quatre plutôt que trois — et surtout quatre **différents**, là où FT8
     * répète le même. Une transmission FT4 dure quatre secondes et demie : y
     * mettre quatre motifs distincts permet au récepteur de savoir *où* il est
     * tombé dans la trame, pas seulement qu'il est tombé sur un repère.
     */
    val COSTAS_1 = intArrayOf(0, 1, 3, 2)
    val COSTAS_2 = intArrayOf(1, 0, 2, 3)
    val COSTAS_3 = intArrayOf(2, 3, 1, 0)
    val COSTAS_4 = intArrayOf(3, 2, 0, 1)

    const val SYMBOLES = 105
    const val SYMBOLES_DONNEES = 87
    const val BITS = 174
    const val BITS_UTILES = 91
    const val BITS_MESSAGE = 77

    /** 12000 / 576 = 20,8333 bauds, et autant de hertz d'écartement. */
    const val DUREE_SYMBOLE_S = 576.0 / 12000.0

    /**
     * La trame : R, S1, A, S2, B, S3, C, S4, R.
     *
     * Les 87 symboles utiles sont coupés en trois groupes de 29, et les
     * symboles R aux deux bouts — de ton 0 — ne portent rien : ils servent à
     * monter et descendre l'amplitude en douceur, pour ne pas claquer à
     * l'ouverture et à la fermeture du manipulateur.
     */
    private val REPERES: List<Pair<Int, IntArray>> = listOf(
        1 to COSTAS_1, 34 to COSTAS_2, 67 to COSTAS_3, 100 to COSTAS_4
    )

    /** Vrai si ce symbole de canal ne porte pas d'information. */
    fun estRepere(position: Int): Boolean {
        if (position == 0 || position == SYMBOLES - 1) return true   // les rampes
        return REPERES.any { (debut, _) -> position in debut until debut + 4 }
    }

    /** Les positions de synchronisation et le ton attendu, pour le démodulateur. */
    fun synchro(): List<Pair<Int, Int>> {
        val l = ArrayList<Pair<Int, Int>>(16)
        for ((debut, reseau) in REPERES) {
            for (i in 0 until 4) l.add((debut + i) to reseau[i])
        }
        return l
    }

    /**
     * Le code de Gray de FT4, dans le sens valeur → ton.
     *
     * L'article donne la correspondance dans l'autre sens : le ton 0 porte 00,
     * le 1 porte 01, le 2 porte 11 et le 3 porte 10. Retournée, elle donne bien
     * la table ci-dessous — et l'on retrouve les quatre premières valeurs du
     * code de FT8, ce qui n'est pas un hasard.
     */
    private val GRAY = intArrayOf(0, 1, 3, 2)
    private val GRAY_INVERSE = IntArray(4).also { inv ->
        GRAY.forEachIndexed { valeur, ton -> inv[ton] = valeur }
    }

    /**
     * La séquence de brouillage, appliquée aux 77 bits **avant** le contrôle et
     * la parité.
     *
     * Sans elle, un message d'appel — qui commence par une longue suite de
     * zéros — produirait un signal à peu près constant sur le ton 0 : une
     * porteuse, pas une modulation. Le récepteur l'applique une seconde fois
     * pour retrouver le message, le ou exclusif étant son propre inverse.
     */
    private const val BROUILLAGE =
        "0100101001011110100010" +
        "0110110100101100001000" +
        "1010011110010101010110" +
        "11111000101"

    private val MASQUE = BooleanArray(BITS_MESSAGE) { BROUILLAGE[it] == '1' }

    /** Applique — ou retire, c'est le même geste — le brouillage. */
    fun brouille(message: BooleanArray): BooleanArray {
        require(message.size >= BITS_MESSAGE) { "message trop court" }
        val out = message.copyOf()
        for (i in 0 until BITS_MESSAGE) out[i] = out[i] xor MASQUE[i]
        return out
    }

    /** Les 87 symboles utiles, extraits des 105 reçus. */
    fun donnees(tons: IntArray): IntArray {
        require(tons.size == SYMBOLES) { "il faut $SYMBOLES symboles" }
        val out = IntArray(SYMBOLES_DONNEES)
        var j = 0
        for (i in 0 until SYMBOLES) if (!estRepere(i)) out[j++] = tons[i]
        return out
    }

    /** Les 105 symboles reçus deviennent 174 bits. */
    fun symbolesVersBits(tons: IntArray): BooleanArray {
        val bits = BooleanArray(BITS)
        var i = 0
        donnees(tons).forEach { ton ->
            val v = GRAY_INVERSE[ton and 3]
            bits[i++] = (v shr 1) and 1 == 1
            bits[i++] = v and 1 == 1
        }
        return bits
    }

    /** Les 174 bits redeviennent 105 symboles, repères et rampes compris. */
    fun bitsVersSymboles(bits: BooleanArray): IntArray {
        require(bits.size == BITS) { "il faut $BITS bits" }
        val tons = IntArray(SYMBOLES)
        var b = 0
        for (i in 0 until SYMBOLES) {
            if (i == 0 || i == SYMBOLES - 1) { tons[i] = 0; continue }  // rampes
            val repere = REPERES.firstOrNull { (debut, _) -> i in debut until debut + 4 }
            tons[i] = if (repere != null) {
                repere.second[i - repere.first]
            } else {
                val v = (if (bits[b]) 2 else 0) or (if (bits[b + 1]) 1 else 0)
                b += 2
                GRAY[v]
            }
        }
        return tons
    }

    /**
     * Combien de repères tombent juste, sur les seize.
     *
     * Les rampes ne comptent pas : leur amplitude monte et descend, et l'on
     * n'attend pas d'y lire un ton franc.
     */
    fun scoreCostas(tons: IntArray): Int {
        require(tons.size == SYMBOLES) { "il faut $SYMBOLES symboles" }
        var bons = 0
        for ((position, attendu) in synchro()) if (tons[position] == attendu) bons++
        return bons
    }

    /**
     * Le contrôle, calculé comme pour FT8 mais sur le message **brouillé**.
     *
     * L'ordre compte : brouiller puis contrôler. Contrôler puis brouiller
     * donnerait un contrôle qui ne correspond à rien de ce qui passe sur l'air.
     */
    fun avecControle(message: BooleanArray): BooleanArray =
        Ft8.avecControle(brouille(message))

    /** Vrai si les 91 bits reçus forment un mot cohérent. */
    fun controleJuste(utiles: BooleanArray): Boolean = Ft8.controleJuste(utiles)

    /** Retrouve les 77 bits du message à partir des 91 bits reçus. */
    fun message(utiles: BooleanArray): BooleanArray =
        brouille(utiles.copyOf(BITS_MESSAGE))
}
