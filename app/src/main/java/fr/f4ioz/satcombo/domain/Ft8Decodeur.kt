/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.domain

import kotlin.math.log10

/**
 * Le décodage complet d'une tranche d'écoute : de l'audio aux messages.
 *
 * C'est la pièce qui manquait entre `Ft8Signal`, qui sait lire des tons, et
 * `Ft8`, qui sait lire un message. Elle enchaîne les deux et **tranche** : un
 * candidat dont le contrôle ne tombe pas juste n'est pas affiché, pas même
 * signalé.
 *
 * **Le contrôle est la seule barrière, et il faut savoir ce qu'elle vaut.**
 * Sans code correcteur, les 83 bits de parité sont ignorés et un seul bit faux
 * condamne le message. Ce qui protège de l'invention est le CRC sur quatorze
 * bits : une suite de bits tirée du bruit a une chance sur seize mille de le
 * satisfaire. Avec trente-deux candidats par tranche et quatre tranches par
 * minute, cela fait un faux message toutes les deux heures environ — rare, mais
 * pas jamais. C'est pourquoi [Decode] porte le rapport signal sur bruit :
 * un message correct à −20 dB alors que ce décodeur ne descend pas sous zéro
 * est un message à ne pas croire.
 */
object Ft8Decodeur {

    /**
     * Un message décodé, avec de quoi juger s'il faut le croire.
     */
    data class Decode(
        val message: Ft8.Message,
        /** Fréquence audio du ton le plus bas, en hertz. */
        val frequenceHz: Double,
        /** Instant du début de la transmission dans la tranche, en secondes. */
        val instantS: Double,
        /** Rapport signal sur bruit rapporté à 2500 Hz, comme un report FT8. */
        val rapportDb: Int,
        /** Le score de synchronisation, pour trier et pour diagnostiquer. */
        val scoreSynchro: Float
    )

    /**
     * La largeur de bruit d'une analyse d'un symbole.
     *
     * Une transformée sur exactement un symbole a une bande de bruit égale à
     * l'inverse de sa durée — 6,25 Hz pour FT8. Le report FT8 se réfère lui à
     * 2500 Hz, d'où l'écart constant entre les deux mesures.
     */
    private fun correctionDb(mode: Ft8Signal.Mode): Double =
        10.0 * log10(2500.0 / mode.ecartHz)

    /**
     * Décode une tranche d'audio.
     *
     * [cadenceHz] est celle de la capture, quelle qu'elle soit : on ramène
     * nous-mêmes à la cadence interne du mode.
     *
     * Rend les messages du plus franc au plus faible. La liste est vide quand
     * rien ne passe le contrôle — ce qui est le cas le plus fréquent, et
     * normal.
     */
    fun decode(
        audio: FloatArray,
        cadenceHz: Double,
        mode: Ft8Signal.Mode = Ft8Signal.FT8,
        basseHz: Double = 200.0,
        hauteHz: Double = 3000.0,
        maximumCandidats: Int = 32
    ): List<Decode> {
        if (mode.synchro.isEmpty()) return emptyList()
        val ramene = Ft8Signal.reechantillonne(audio, cadenceHz, mode.cadenceHz)
        if (ramene.size < mode.parSymbole * mode.symboles) return emptyList()

        val spec = Ft8Signal.spectrogramme(ramene, mode, basseHz, hauteHz)
        val candidats = Ft8Signal.candidats(spec, maximum = maximumCandidats)

        val sortie = ArrayList<Decode>()
        val dejaVus = HashSet<String>()
        for (c in candidats) {
            // --- le code correcteur d'abord ---
            //
            // On lui donne les vraisemblances douces et on prend ce qu'il rend.
            // S'il ne converge pas, on retombe sur la décision dure : elle ne
            // coûte rien et sauve les signaux très forts que le décodeur
            // pourrait bouder si le graphe oscille.
            val douces = Ft8Signal.vraisemblances(spec, c)
            val corrige = if (douces.size >= LdpcTables.N) Ldpc.decode(douces).bits else null

            val tons = Ft8Signal.tons(spec, c)
            // Les deux modes divergent ici et nulle part ailleurs : FT4 range
            // deux bits par symbole au lieu de trois, et brouille les 77 bits
            // du message avant le contrôle. Au-delà, même contrôle, même
            // format, même dépliage.
            val quatreTons = mode.tons == 4
            val bits = corrige ?: (if (quatreTons) Ft4.symbolesVersBits(tons)
                       else Ft8.symbolesVersBits(tons))
            val utiles = bits.copyOf(Ft8.BITS_UTILES)
            // Le contrôle d'abord : inutile de déplier ce qui est faux.
            if (!Ft8.controleJuste(utiles)) continue
            val brut = if (quatreTons) Ft4.message(utiles)
                       else utiles.copyOf(Ft8.BITS_MESSAGE)
            val message = Ft8.deplie(brut) ?: continue
            // Deux candidats voisins peuvent livrer le même message ; on ne
            // l'écrit qu'une fois, en gardant le plus franc — la liste arrive
            // déjà triée par score.
            if (!dejaVus.add(message.brut)) continue
            sortie.add(Decode(
                message = message,
                frequenceHz = c.frequenceHz(spec),
                instantS = c.instantS(spec),
                rapportDb = rapportDb(spec, c, mode),
                scoreSynchro = c.score
            ))
        }
        return sortie
    }

    /**
     * Estime le rapport signal sur bruit d'un candidat, en décibels dans
     * 2500 Hz.
     *
     * Aux positions de synchronisation, le ton attendu porte le signal **et** le
     * bruit, tandis que les autres tons ne portent que le bruit. Leur différence
     * donne le signal, leur moyenne donne le bruit, et le rapport se ramène
     * ensuite à la largeur de référence.
     *
     * **Elle sature vers le haut, et il faut le savoir.** Un signal n'a jamais
     * un spectre parfaitement net : le lissage gaussien de ses transitions
     * étale un peu d'énergie de part et d'autre, et cette jupe est
     * indiscernable d'un bruit ambiant. Au-delà d'une quinzaine de décibels,
     * l'estimation cesse donc de monter. Cela ne gêne pas son office — dire si
     * un message mérite d'être cru se joue vers le bas, pas vers le haut — mais
     * un report de SatMe ne se compare pas à celui de WSJT-X pour les stations
     * fortes.
     */
    fun rapportDb(
        spec: Ft8Signal.Spectrogramme,
        c: Ft8Signal.Candidat,
        mode: Ft8Signal.Mode
    ): Int {
        val pasParSymbole = mode.parSymbole / spec.pasTemps
        val largeur = (mode.tons - 1) * spec.raieParTon

        var attendu = 0.0
        var bruitSomme = 0.0
        var bruitComptes = 0L
        for ((position, tonAttendu) in mode.synchro) {
            val t = c.pas + position * pasParSymbole
            attendu += spec.puissance(t, c.raie + tonAttendu * spec.raieParTon).toDouble()
            // Le bruit se mesure **hors** de la bande du signal, et non sur ses
            // autres tons. Le lissage gaussien des transitions y bave, et
            // prendre cette bave pour du bruit plafonnait l'estimation autour de
            // −9 dB : un signal parfaitement propre s'annonçait alors aussi
            // médiocre qu'un signal noyé. On s'écarte donc franchement : la
            // jupe du lissage gaussien décroît vite, et six raies de garde la
            // laissent derrière.
            for (d in 6..24) {
                val bas = c.raie - d
                val haut = c.raie + largeur + d
                if (bas >= 0) { bruitSomme += spec.puissance(t, bas).toDouble(); bruitComptes++ }
                if (haut < spec.nbRaies) {
                    bruitSomme += spec.puissance(t, haut).toDouble(); bruitComptes++
                }
            }
        }
        if (bruitComptes == 0L) return -99
        val n = mode.synchro.size.toDouble()
        val bruit = bruitSomme / bruitComptes
        val signal = (attendu / n) - bruit
        if (bruit <= 0.0 || signal <= 0.0) return -99
        val brut = 10.0 * log10(signal / bruit) - correctionDb(mode)
        // Les reports FT8 vont de −24 à +30 ; au-delà, la mesure ne veut plus
        // rien dire et un nombre extravagant ferait douter du reste.
        return brut.coerceIn(-30.0, 30.0).toInt()
    }
}
