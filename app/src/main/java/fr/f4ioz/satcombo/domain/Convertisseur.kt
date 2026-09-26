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
 * Le chaînon manquant entre la fréquence du satellite et celle du poste.
 *
 * Jusqu'ici SatMe faisait une hypothèse jamais écrite nulle part : que la
 * fréquence du satellite est celle qu'on affiche sur la radio. C'est vrai du 2
 * m au 23 cm, et faux partout ailleurs. Sur QO-100 la descente est à 10 489
 * MHz et la montée à 2 400 MHz — aucun poste d'amateur ne va là directement.
 * Entre l'antenne et la radio il y a une boîte qui décale tout d'un coup : un
 * LNB à la réception, un transverter à l'émission.
 *
 * Cette boîte se résume à un oscillateur local et à un sens. Rien de plus.
 *
 * **Injection basse** (le cas ordinaire, et de loin) : l'oscillateur est sous
 * la bande, et l'on soustrait. Un LNB d'OL 9 750 MHz ramène la balise médiane
 * de QO-100, 10 489,750, à 739,750 MHz. Un transverter 2 400 piloté en 432 a
 * un OL de 1 968 : on lui présente 432,050 et il émet sur 2 400,050. C'est la
 * même soustraction dans les deux cas, à condition de toujours l'écrire dans
 * le même sens — du satellite vers le poste. D'où le nom des deux méthodes,
 * qui ne laissent pas le choix.
 *
 * **Injection haute** : l'oscillateur est au-dessus de la bande, et le
 * spectre se retourne. La conversion devient `OL − f`, qui est sa propre
 * réciproque : monter et redescendre par la même formule rend la fréquence de
 * départ. Peu de matériel amateur fonctionne ainsi, mais il en existe, et
 * l'oublier coûterait une bande à l'envers impossible à diagnostiquer.
 *
 * ### Pourquoi une plage, et pas seulement un OL
 *
 * Parce qu'un convertisseur n'existe que sur sa bande. Le LNB posé au foyer
 * de la parabole ne voit pas le 145 MHz, et lui appliquer sa soustraction
 * donnerait une fréquence négative. Sans borne, un convertisseur oublié actif
 * casserait tous les autres satellites — on cocherait QO-100 un dimanche, et
 * l'ISS ne marcherait plus le mardi sans qu'aucun message n'explique
 * pourquoi. Avec les bornes, le convertisseur se retire tout seul de la
 * chaîne dès qu'on sort de sa bande, et l'application se comporte exactement
 * comme avant sur tout le reste.
 *
 * C'est aussi ce qui permet de laisser en place, en permanence, un LNB et un
 * transverter : ils ne se marchent pas dessus, leurs plages ne se touchent
 * pas.
 *
 * ### Ce que cette pièce ne fait pas
 *
 * Elle ne connaît ni Android, ni le poste, ni la clé — elle se juge donc au
 * banc, sans matériel. Elle ne corrige pas la dérive du LNB : c'est le rôle
 * du décalage d'étalonnage, qui existe déjà par satellite et se règle sur la
 * balise. Et elle ne dit rien du Doppler, qui s'applique à la fréquence du
 * satellite, avant elle, et jamais après.
 */
data class Convertisseur(
    /** Coché ou non. Décoché, la pièce est transparente : rien ne bouge. */
    val actif: Boolean = false,
    /** L'oscillateur local, en hertz. Zéro vaut « pas de convertisseur ». */
    val olHz: Long = 0L,
    /** Injection haute : le spectre se retourne, `poste = OL − satellite`. */
    val inverseur: Boolean = false,
    /** Borne basse de la bande couverte, côté satellite. 0 = pas de borne. */
    val basHz: Long = 0L,
    /** Borne haute de la bande couverte, côté satellite. 0 = pas de borne. */
    val hautHz: Long = 0L,
) {

    /** Réglé de façon exploitable : coché, avec un oscillateur crédible. */
    val configure: Boolean get() = actif && olHz > 0L

    /**
     * Ce convertisseur a-t-il quelque chose à voir avec cette fréquence de
     * satellite ? Hors de sa plage, il n'est pas dans la chaîne.
     */
    fun couvre(satHz: Long): Boolean {
        if (!configure) return false
        if (basHz > 0L && satHz < basHz) return false
        if (hautHz > 0L && satHz > hautHz) return false
        return versPosteBrut(satHz) > 0L
    }

    /**
     * Du satellite vers le poste : ce qu'il faut afficher sur la radio, ou
     * écrire dans la PLL de la clé, pour être sur [satHz] dans le ciel.
     *
     * Hors plage, la fréquence ressort intacte. C'est délibéré : un
     * convertisseur resté coché ne doit pas empêcher de travailler l'ISS.
     */
    fun versPoste(satHz: Long): Long =
        if (couvre(satHz)) versPosteBrut(satHz) else satHz

    /**
     * Du poste vers le satellite : à quoi correspond, dans le ciel, ce que la
     * radio affiche. C'est le sens de la relecture — sans lui, un geste sur la
     * molette serait lu comme un saut de 9 750 MHz.
     */
    /**
     * Cette fréquence peut-elle être une intermédiaire **de ce convertisseur** ?
     *
     * C'est la garde qui manquait. La remontée ne vérifiait que son résultat :
     * elle convertissait tout ce qui, une fois converti, tombait dans la bande
     * du satellite. Or la descente d'un LEO en 145,95 MHz, remontée par un LNB
     * d'oscillateur 10 344,973, donne 10 490,9 MHz — soit exactement dans les
     * bornes de QO-100. Le convertisseur l'acceptait donc, et l'écran d'un
     * satellite à défilement affichait des gigahertz.
     *
     * Vérifier l'entrée autant que la sortie ferme cette classe de défaut
     * d'un coup, quel que soit l'appelant : une fréquence n'est une
     * intermédiaire que si elle tombe dans la fenêtre que ce convertisseur
     * produit réellement.
     */
    fun accepteEnEntree(posteHz: Long): Boolean {
        if (!configure) return false
        if (basHz <= 0L || hautHz <= 0L) return true
        val fiBasse = versPosteBrut(if (inverseur) hautHz else basHz)
        val fiHaute = versPosteBrut(if (inverseur) basHz else hautHz)
        return posteHz in minOf(fiBasse, fiHaute)..maxOf(fiBasse, fiHaute)
    }

    fun versSatellite(posteHz: Long): Long {
        if (!configure) return posteHz
        // L'entrée d'abord : une fréquence qui n'est pas une intermédiaire de
        // ce convertisseur ne lui appartient pas, quoi que donne le calcul.
        if (!accepteEnEntree(posteHz)) return posteHz
        val sat = versSatelliteBrut(posteHz)
        return if (couvre(sat)) sat else posteHz
    }

    private fun versPosteBrut(satHz: Long): Long =
        if (inverseur) olHz - satHz else satHz - olHz

    private fun versSatelliteBrut(posteHz: Long): Long =
        if (inverseur) olHz - posteHz else posteHz + olHz

    companion object {
        /** Rien du tout : la chaîne directe, celle de tous les autres satellites. */
        val AUCUN = Convertisseur()

        /** Le haut de la bande Ku amateur, où vit la descente de QO-100. */
        private const val KU_BAS = 10_400_000_000L
        private const val KU_HAUT = 10_800_000_000L

        /** Le 13 cm, où vit la montée de QO-100. */
        private const val S_BAS = 2_390_000_000L
        private const val S_HAUT = 2_450_000_000L

        /**
         * Les quatre montages qu'on rencontre neuf fois sur dix.
         *
         * Ce ne sont que des points de départ : l'OL reste modifiable à la
         * main, parce qu'il existe des LNB à 10 057,5 MHz — qui ramènent la
         * balise médiane à 432,25 MHz, donc dans une bande que l'IC-9700 reçoit
         * — et que chacun bricole le sien.
         *
         * Deux d'entre eux méritent un mot, parce qu'ils sont les seuls à
         * poser la descente dans une bande qu'un poste d'amateur reçoit
         * vraiment. `lnb10057` sort en 432,250 ; `down145` sort en 144,777, et
         * range tout le transpondeur étroit entre 144,532 et 145,024 — soit
         * dans les 2 m, avec de la marge des deux côtés. C'est ce dernier qui
         * va avec un upconverter piloté en 432 : le poste reçoit en 145 et
         * émet en 432, une paire croisée qu'il sait tenir depuis toujours.
         */
        val PRESETS: List<Preset> = listOf(
            Preset("lnb9750", 9_750_000_000L, KU_BAS, KU_HAUT, descente = true),
            Preset("lnb10000", 10_000_000_000L, KU_BAS, KU_HAUT, descente = true),
            Preset("lnb10057", 10_057_500_000L, KU_BAS, KU_HAUT, descente = true),
            // **Mesuré, pas théorique.** 10 344,972 94 MHz relevés sur la
            // chaîne Bullseye + DX Patrol de F4IOZ, le 3 septembre 2026, par
            // comparaison avec le WebSDR IS0GRB — lui-même sur GPSDO, donc
            // référence absolue. Deux relevés séparés, à dix hertz l'un de
            // l'autre.
            //
            // Les 973 kHz d'écart au nominal ne sont pas une erreur à
            // corriger : c'est le TCXO du LNB, environ 2,6 ppm à 9 750 MHz,
            // conforme à sa spécification. Le décalage est **constant et
            // reproductible d'un allumage à l'autre** — 0,85 kHz de dispersion
            // sur deux heures avec extinction complète entre les essais.
            //
            // Un GPSDO ne le corrigera pas : il ne touche pas au LNB, dont
            // l'oscillateur est interne. Attendre que l'étalonnage revienne à
            // zéro parce qu'on a verrouillé le reste de la chaîne serait une
            // erreur de raisonnement.
            Preset("down145", 10_344_973_000L, KU_BAS, KU_HAUT, descente = true),
            Preset("tvtr432", 1_968_000_000L, S_BAS, S_HAUT, descente = false),
            Preset("tvtr144", 2_256_000_000L, S_BAS, S_HAUT, descente = false),
        )

        /** Un montage tout fait. [cle] sert à retrouver le libellé traduit. */
        data class Preset(
            val cle: String,
            val olHz: Long,
            val basHz: Long,
            val hautHz: Long,
            /** Vrai pour un LNB (réception), faux pour un transverter (émission). */
            val descente: Boolean,
        ) {
            fun vers(): Convertisseur = Convertisseur(
                actif = true, olHz = olHz, inverseur = false,
                basHz = basHz, hautHz = hautHz)
        }

        /**
         * La fréquence intermédiaire d'un préréglage, pour l'afficher pendant
         * qu'on choisit : « LNB 9750 → 739,750 MHz ». Voir un nombre familier
         * apparaître est la seule vérification possible avant d'avoir branché.
         */
        const val BALISE_MEDIANE_HZ = 10_489_750_000L

        /**
         * La balise CW haute, engendrée au sol elle aussi.
         *
         * C'est un **second point d'étalonnage, à 500 kHz du premier**. Deux
         * points valent mieux qu'un : si les deux donnent le même oscillateur
         * local, l'erreur est un décalage constant, qu'un seul nombre corrige.
         * Si l'écart diffère entre les deux, c'est une pente — l'oscillateur
         * n'est pas seulement décalé, il est faux — et aucun décalage unique
         * ne rattrapera les deux bouts du transpondeur à la fois.
         */
        const val BALISE_HAUTE_HZ = 10_490_000_000L
    }
}
