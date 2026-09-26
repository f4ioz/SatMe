/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.domain

import kotlin.math.abs
import kotlin.math.min

/**
 * Le code correcteur de FT8 et FT4 : (174, 91), soit 83 bits de parité pour 91
 * bits utiles.
 *
 * **Ce qu'il change.** Jusqu'ici SatMe s'en remettait au seul contrôle de
 * quatorze bits : un bit faux et le message était perdu. Il fallait donc que
 * les 174 bits sortent tous justes du démodulateur, ce qui n'arrive qu'avec des
 * signaux confortables. Le code correcteur repêche typiquement une quinzaine de
 * bits faux — c'est la différence entre entendre les stations fortes et
 * entendre la bande.
 *
 * **Comment il s'y prend.** Chaque bit participe à trois contrôles de parité,
 * chaque contrôle porte sur six ou sept bits. Le graphe qui relie les uns aux
 * autres n'a pas de cycle court, et l'on peut donc y faire circuler des
 * croyances : chaque contrôle dit à chacun de ses bits ce que les autres
 * laissent penser de lui, le bit fait la somme, et l'on recommence. Au bout de
 * quelques tours, ou bien tous les contrôles tombent juste — et c'est fini —
 * ou bien la chose ne converge pas, et l'on ne rend rien.
 *
 * **Min-sum normalisé plutôt que somme-produit.** La règle exacte demande des
 * tangentes hyperboliques à chaque arête et à chaque tour ; le min-sum les
 * remplace par un minimum et un signe, au prix d'une fraction de décibel qu'un
 * facteur d'échelle rattrape en grande partie. Sur un téléphone qui doit
 * traiter une trentaine de candidats en moins de deux secondes et demie, ce
 * n'est pas une optimisation prématurée : c'est la différence entre décoder
 * pendant la tranche et décoder après.
 */
object Ldpc {

    /**
     * Le facteur d'échelle du min-sum.
     *
     * Le minimum surestime toujours la confiance d'un contrôle ; sans
     * correction, le décodeur se persuade trop vite et s'enferme sur une
     * mauvaise réponse. 0,75 est la valeur usuelle pour les codes de ce degré.
     */
    private const val ECHELLE = 0.75f

    /** Résultat d'un décodage. */
    data class Resultat(
        /** Les 174 bits corrigés, ou `null` si rien n'a convergé. */
        val bits: BooleanArray?,
        /** Combien de contrôles restent violés : zéro quand c'est bon. */
        val restants: Int,
        /** Combien de tours ont été nécessaires. */
        val tours: Int
    )

    /**
     * Vérifie les 83 contrôles de parité et rend combien sont violés.
     *
     * Zéro ne prouve pas que le message est juste — un mot de code faux est
     * toujours un mot de code — mais c'est la condition nécessaire, et le
     * contrôle de quatorze bits se charge ensuite du reste.
     */
    fun controlesViolés(bits: BooleanArray): Int {
        var mauvais = 0
        for (m in 0 until LdpcTables.M) {
            var parite = false
            for (n in LdpcTables.bitsDuControle[m]) parite = parite xor bits[n]
            if (parite) mauvais++
        }
        return mauvais
    }

    /**
     * Décode à partir des vraisemblances, une par bit.
     *
     * Convention : **positif signifie zéro probable**, négatif signifie un, et
     * l'amplitude porte la confiance. C'est celle que rend
     * [Ft8Signal.vraisemblances], et elle ne doit surtout pas se retourner en
     * route — un décodeur nourri de signes inversés converge tranquillement
     * vers l'inverse du message, sans rien signaler.
     *
     * Rend `null` dans [Resultat.bits] si aucun tour ne satisfait tous les
     * contrôles. **C'est une vraie branche** : rendre le mot le plus probable
     * malgré des contrôles violés reviendrait à inventer, et un indicatif
     * inventé finit dans un carnet.
     */
    fun decode(vraisemblances: FloatArray, toursMax: Int = 30): Resultat {
        require(vraisemblances.size >= LdpcTables.N) {
            "il faut ${LdpcTables.N} vraisemblances"
        }
        val n = LdpcTables.N
        val m = LdpcTables.M

        // Les messages qui circulent sur les arêtes, rangés par contrôle.
        val versBit = Array(m) { FloatArray(LdpcTables.nbParContole[it]) }
        val total = FloatArray(n)
        val bits = BooleanArray(n)

        for (tour in 1..toursMax) {
            // --- des bits vers les contrôles, puis retour ---
            for (i in 0 until n) total[i] = vraisemblances[i]
            for (c in 0 until m) {
                val liste = LdpcTables.bitsDuControle[c]
                for (j in liste.indices) total[liste[j]] += versBit[c][j]
            }

            for (c in 0 until m) {
                val liste = LdpcTables.bitsDuControle[c]
                val sortant = versBit[c]
                // Le message d'un contrôle vers un bit ne doit pas contenir ce
                // que ce bit lui a dit : sinon la croyance se renforce
                // elle-même et le décodeur se convainc de n'importe quoi.
                var signe = 1
                var min1 = Float.MAX_VALUE
                var min2 = Float.MAX_VALUE
                var argMin = 0
                for (j in liste.indices) {
                    val v = total[liste[j]] - sortant[j]
                    if (v < 0f) signe = -signe
                    val a = abs(v)
                    if (a < min1) { min2 = min1; min1 = a; argMin = j }
                    else if (a < min2) { min2 = a }
                }
                for (j in liste.indices) {
                    val v = total[liste[j]] - sortant[j]
                    val s = if (v < 0f) -signe else signe   // on retire son propre signe
                    val ampleur = if (j == argMin) min2 else min1
                    sortant[j] = s * ECHELLE * ampleur
                }
            }

            // --- décision et contrôle ---
            for (i in 0 until n) total[i] = vraisemblances[i]
            for (c in 0 until m) {
                val liste = LdpcTables.bitsDuControle[c]
                for (j in liste.indices) total[liste[j]] += versBit[c][j]
            }
            for (i in 0 until n) bits[i] = total[i] < 0f

            val mauvais = controlesViolés(bits)
            if (mauvais == 0) return Resultat(bits.copyOf(), 0, tour)
        }
        return Resultat(null, controlesViolés(bits), toursMax)
    }

    /**
     * Calcule les 83 bits de parité d'un message de 91 bits.
     *
     * Sert au banc — corrompre un mot de code et vérifier qu'il se répare — et
     * servira à l'émission le jour venu.
     */
    fun encode(utiles: BooleanArray): BooleanArray {
        require(utiles.size >= LdpcTables.K) { "il faut ${LdpcTables.K} bits utiles" }
        val sortie = BooleanArray(LdpcTables.N)
        utiles.copyInto(sortie, 0, 0, LdpcTables.K)
        for (m in 0 until LdpcTables.M) {
            var parite = false
            val ligne = LdpcTables.generateur[m]
            for (k in 0 until LdpcTables.K) if (ligne[k] && utiles[k]) parite = !parite
            sortie[LdpcTables.K + m] = parite
        }
        return sortie
    }

    /** Les vraisemblances qu'on aurait pour un mot de code parfaitement reçu. */
    fun vraisemblancesParfaites(bits: BooleanArray, force: Float = 4f): FloatArray =
        FloatArray(LdpcTables.N) { if (bits[it]) -force else force }

    /** Le nombre de bits qui diffèrent entre deux mots. */
    fun distance(a: BooleanArray, b: BooleanArray): Int {
        var d = 0
        for (i in 0 until min(a.size, b.size)) if (a[i] != b[i]) d++
        return d
    }
}
