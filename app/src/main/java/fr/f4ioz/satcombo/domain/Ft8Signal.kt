/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * La partie radio : aller de l'audio aux symboles.
 *
 * `Ft8.kt` savait déjà passer des symboles aux bits et des bits au message.
 * Il manquait tout l'amont — trouver le signal dans le bruit, savoir quand il
 * commence et sur quelle fréquence, et lire ses tons. C'est ce fichier.
 *
 * **Ce qu'on peut éprouver sans radio.** Tout, ici. On fabrique le signal
 * soi-même à partir de symboles connus, on le décale dans le temps et en
 * fréquence, on y verse du bruit, et l'on regarde si les mêmes symboles
 * ressortent. Le banc n'est pas une imitation du terrain : pour cette
 * partie-là, il est plus sévère, parce qu'on connaît la vérité.
 *
 * **Le choix de la cadence interne.** On rééchantillonne vers une cadence où un
 * symbole fait exactement une puissance de deux : 2048 points pour FT8, 512
 * pour FT4. Deux choses en découlent, et elles valent la conversion :
 *
 * - la transformée de Fourier est une radix-2 ordinaire, courte à écrire et
 *   rapide à exécuter ;
 * - les tons tombent **exactement** sur des raies, sans fuite spectrale. À
 *   6,25 Hz d'écartement et 6,25 Hz de résolution, deux tons voisins sont
 *   rigoureusement orthogonaux — ce qui n'est vrai que si la durée d'analyse
 *   fait exactement un symbole.
 *
 * La cadence du micro n'a donc plus d'importance : 44 100, 48 000 ou 16 000,
 * on ramène. C'est aussi ce qui évite de dépendre de ce qu'Android voudra bien
 * accorder à `AudioRecord` selon le téléphone.
 */
object Ft8Signal {

    /**
     * Ce qui distingue FT8 de FT4, rassemblé pour que la chaîne soit commune.
     *
     * Les deux modes partagent le même format de message, le même CRC et le
     * même code correcteur : seule la couche physique diffère. Écrire deux
     * démodulateurs serait écrire deux fois le même, et les faire diverger.
     */
    data class Mode(
        val nom: String,
        /** Nombre de tons : 8 pour FT8, 4 pour FT4. */
        val tons: Int,
        /** Échantillons par symbole — une puissance de deux, par construction. */
        val parSymbole: Int,
        /** Durée d'un symbole, en secondes. */
        val dureeSymbole: Double,
        /** Nombre total de symboles de canal. */
        val symboles: Int,
        /** Les positions des symboles de synchronisation et le ton attendu. */
        val synchro: List<Pair<Int, Int>>,
        /**
         * Le produit largeur-durée de la mise en forme gaussienne.
         *
         * Il n'est **pas** le même pour les deux modes, et je l'avais supposé
         * tel : l'article de K1JT, K9AN et G4WJS donne BT = 2 pour FT8 et
         * BT = 1 pour FT4, dont l'impulsion est plus fortement lissée. Un FT4
         * synthétisé à BT = 2 serait trop large et gênerait ses voisins.
         */
        val lissageBT: Double = 2.0
    ) {
        /** La cadence interne : celle qui rend [parSymbole] exact. */
        val cadenceHz: Double get() = parSymbole / dureeSymbole
        /** L'écartement des tons, égal à la rapidité de modulation. */
        val ecartHz: Double get() = 1.0 / dureeSymbole
        val dureeS: Double get() = symboles * dureeSymbole
    }

    /** Les positions de synchronisation de FT8 : trois réseaux de sept. */
    private fun synchroFt8(): List<Pair<Int, Int>> {
        val l = ArrayList<Pair<Int, Int>>(21)
        for (depart in intArrayOf(0, 36, 72)) {
            for (i in 0 until 7) l.add((depart + i) to Ft8.COSTAS[i])
        }
        return l
    }

    val FT8 = Mode(
        nom = "FT8",
        tons = 8,
        parSymbole = 2048,           // 2048 / 0,16 s = 12 800 Hz
        dureeSymbole = Ft8.DUREE_SYMBOLE_S,
        symboles = Ft8.SYMBOLES,
        synchro = synchroFt8(),
        lissageBT = 2.0
    )

    /**
     * FT4, désormais complet.
     *
     * Les paramètres et les quatre réseaux de Costas viennent de la description
     * du protocole publiée par K9AN, G4WJS et K1JT dans QEX — que les auteurs
     * placent dans le domaine public. Rien n'est repris du code de WSJT-X, qui
     * lui reste sous GPL.
     *
     * BT = 1 et non 2 : l'impulsion de FT4 est plus fortement lissée que celle
     * de FT8. Synthétisée à 2, elle serait trop large et gênerait ses voisins.
     */
    val FT4 = Mode(
        nom = "FT4",
        tons = 4,
        parSymbole = 512,            // 512 / 0,048 s = 10 666,67 Hz
        dureeSymbole = Ft4.DUREE_SYMBOLE_S,
        symboles = Ft4.SYMBOLES,
        synchro = Ft4.synchro(),
        lissageBT = 1.0
    )

    // ------------------------------------------------------- rééchantillonnage

    /**
     * Ramène un bloc audio à la cadence voulue, par interpolation cubique.
     *
     * Cubique et non linéaire : l'interpolation linéaire d'un signal à 3 kHz
     * échantillonné à 12 kHz introduit une distorsion de l'ordre du pour cent,
     * qui se traduit par de la fuite entre tons voisins. Ce sont précisément
     * les tons voisins qu'on cherche à distinguer.
     */
    fun reechantillonne(entree: FloatArray, deHz: Double, versHz: Double): FloatArray {
        if (entree.isEmpty()) return FloatArray(0)
        if (kotlin.math.abs(deHz - versHz) < 1e-9) return entree.copyOf()
        val pas = deHz / versHz
        val sortie = FloatArray(((entree.size / pas).toInt()).coerceAtLeast(0))
        for (n in sortie.indices) {
            val x = n * pas
            val i = x.toInt()
            val f = (x - i).toFloat()
            // Catmull-Rom, avec les bords tenus par répétition plutôt que par
            // des zéros : un zéro au bord est une marche, et une marche est un
            // large étalement spectral.
            val p0 = entree[(i - 1).coerceIn(0, entree.size - 1)]
            val p1 = entree[i.coerceIn(0, entree.size - 1)]
            val p2 = entree[(i + 1).coerceIn(0, entree.size - 1)]
            val p3 = entree[(i + 2).coerceIn(0, entree.size - 1)]
            sortie[n] = (0.5f * ((2f * p1) + (-p0 + p2) * f +
                (2f * p0 - 5f * p1 + 4f * p2 - p3) * f * f +
                (-p0 + 3f * p1 - 3f * p2 + p3) * f * f * f))
        }
        return sortie
    }

    // ------------------------------------------------------------------- FFT

    /**
     * Transformée de Fourier en place, radix-2, sur des tableaux de même
     * longueur — une puissance de deux.
     */
    fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        require(n and (n - 1) == 0) { "la longueur doit être une puissance de deux" }
        // Renversement de bits.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var longueur = 2
        while (longueur <= n) {
            val angle = -2.0 * PI / longueur
            val wr = cos(angle).toFloat()
            val wi = sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until longueur / 2) {
                    val ar = re[i + k]; val ai = im[i + k]
                    val br = re[i + k + longueur / 2]; val bi = im[i + k + longueur / 2]
                    val tr = br * cr - bi * ci
                    val ti = br * ci + bi * cr
                    re[i + k] = ar + tr; im[i + k] = ai + ti
                    re[i + k + longueur / 2] = ar - tr; im[i + k + longueur / 2] = ai - ti
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += longueur
            }
            longueur = longueur shl 1
        }
    }

    // ---------------------------------------------------------- spectrogramme

    /**
     * Les puissances par instant et par raie.
     *
     * Le pas de temps vaut un demi-symbole et le pas de fréquence un demi-écart
     * de tons : c'est le compromis habituel. Un pas plus fin coûte de la mémoire
     * et du temps sans rien apporter, un pas plus grossier laisse passer les
     * signaux mal centrés — et sur un satellite, **rien n'est jamais bien
     * centré** : le décalage Doppler d'un passage bas déplace la porteuse de
     * plusieurs kilohertz en douze secondes.
     *
     * Le demi-écart en fréquence s'obtient en complétant la transformée de
     * zéros sur le double de sa longueur. Cela n'invente pas de résolution :
     * cela interpole, ce qui suffit à retrouver un ton posé entre deux raies.
     */
    class Spectrogramme(
        val mode: Mode,
        /** Puissances, rangées par pas de temps puis par demi-raie. */
        val puissances: Array<FloatArray>,
        /** Pas de temps, en échantillons. */
        val pasTemps: Int,
        /** Fréquence de la demi-raie 0, en hertz. */
        val basseHz: Double
    ) {
        val nbPas: Int get() = puissances.size
        val nbRaies: Int get() = if (puissances.isEmpty()) 0 else puissances[0].size
        /** Deux demi-raies séparent deux tons voisins. */
        val raieParTon: Int get() = 2
        fun puissance(pas: Int, raie: Int): Float {
            if (pas < 0 || pas >= nbPas) return 0f
            if (raie < 0 || raie >= nbRaies) return 0f
            return puissances[pas][raie]
        }
    }

    /**
     * Calcule le spectrogramme d'un bloc audio déjà ramené à la bonne cadence.
     *
     * [basseHz] et [hauteHz] bornent la bande explorée : inutile de porter des
     * raies où aucune station ne se pose, et cela divise d'autant le temps de
     * recherche.
     */
    fun spectrogramme(
        audio: FloatArray,
        mode: Mode,
        basseHz: Double = 200.0,
        hauteHz: Double = 3000.0
    ): Spectrogramme {
        val n = mode.parSymbole
        val nfft = n * 2                       // complété de zéros : demi-raies
        val pasTemps = n / 2                   // demi-symbole
        val binHz = mode.cadenceHz / nfft      // = écart / 2
        val raieBasse = (basseHz / binHz).toInt().coerceAtLeast(0)
        val raieHaute = (hauteHz / binHz).toInt().coerceAtMost(nfft / 2 - 1)
        val nbRaies = (raieHaute - raieBasse + 1).coerceAtLeast(1)

        val nbPas = if (audio.size < n) 0 else (audio.size - n) / pasTemps + 1
        val sortie = Array(nbPas) { FloatArray(nbRaies) }

        val re = FloatArray(nfft)
        val im = FloatArray(nfft)
        for (p in 0 until nbPas) {
            val debut = p * pasTemps
            java.util.Arrays.fill(re, 0f)
            java.util.Arrays.fill(im, 0f)
            // Fenêtre rectangulaire, volontairement. Sur exactement un symbole,
            // deux tons voisins sont orthogonaux ; une fenêtre en cloche
            // détruirait cette orthogonalité pour gagner sur des fuites qui
            // n'existent pas ici.
            for (k in 0 until n) re[k] = audio[debut + k]
            fft(re, im)
            val ligne = sortie[p]
            for (r in 0 until nbRaies) {
                val b = raieBasse + r
                ligne[r] = re[b] * re[b] + im[b] * im[b]
            }
        }
        return Spectrogramme(mode, sortie, pasTemps, raieBasse * binHz)
    }

    // ------------------------------------------------------- synchronisation

    /**
     * Un signal repéré : quand il commence, sur quelle raie, et à quel point les
     * repères concordent.
     */
    data class Candidat(
        /** Décalage en pas de temps depuis le début du bloc. */
        val pas: Int,
        /** Raie du ton 0, en demi-raies. */
        val raie: Int,
        val score: Float
    ) {
        fun frequenceHz(spec: Spectrogramme): Double =
            spec.basseHz + raie * (spec.mode.ecartHz / 2.0)
        fun instantS(spec: Spectrogramme): Double =
            pas * spec.pasTemps / spec.mode.cadenceHz
    }

    /**
     * Cherche les signaux par leurs réseaux de Costas.
     *
     * **Le score est doux, pas un décompte.** On pourrait compter les repères
     * qui tombent juste ; on additionne plutôt, pour chaque repère, l'écart
     * entre la puissance du ton attendu et la puissance moyenne des autres
     * tons. Un décompte jette l'information la plus utile — de combien le bon
     * ton l'emporte — et c'est justement celle qui permet de distinguer un
     * signal faible d'une coïncidence. Le tout est divisé par la puissance
     * moyenne, pour qu'un signal fort et un signal faible se comparent.
     *
     * Rend les candidats du meilleur au moins bon, au plus [maximum].
     */
    fun candidats(
        spec: Spectrogramme,
        maximum: Int = 32,
        scoreMinimal: Float = 1.5f
    ): List<Candidat> {
        val mode = spec.mode
        if (mode.synchro.isEmpty()) return emptyList()
        val pasParSymbole = mode.parSymbole / spec.pasTemps
        val raieDernierTon = (mode.tons - 1) * spec.raieParTon
        val pasMaximum = spec.nbPas - mode.symboles * pasParSymbole
        if (pasMaximum < 0) return emptyList()

        // Le plancher de bruit, mesuré une fois sur tout le spectrogramme.
        //
        // **C'est lui la référence, et non la puissance du candidat.** Diviser
        // par sa propre puissance paraît élégant — cela rend les signaux forts
        // et faibles comparables — mais c'est un piège : un candidat mal aligné
        // n'attrape presque rien, donc divise peu par peu, et ressort avec un
        // score énorme. Le banc l'a montré sans détour, seize places prises par
        // des fantômes du même signal.
        var reference = 0.0
        var comptes = 0L
        for (ligne in spec.puissances) for (v in ligne) { reference += v; comptes++ }
        reference = if (comptes > 0) reference / comptes else 0.0
        if (reference <= 0.0) return emptyList()

        val trouves = ArrayList<Candidat>()
        for (pas in 0..pasMaximum) {
            for (raie in 0 until spec.nbRaies - raieDernierTon) {
                var somme = 0f
                for ((position, tonAttendu) in mode.synchro) {
                    val t = pas + position * pasParSymbole
                    var attendu = 0f
                    var autres = 0f
                    for (ton in 0 until mode.tons) {
                        val p = spec.puissance(t, raie + ton * spec.raieParTon)
                        if (ton == tonAttendu) attendu = p else autres += p
                    }
                    somme += attendu - autres / (mode.tons - 1)
                }
                val score = (somme / mode.synchro.size / reference).toFloat()
                if (score >= scoreMinimal) trouves.add(Candidat(pas, raie, score))
            }
        }
        trouves.sortByDescending { it.score }

        // On ne garde qu'un candidat par fréquence, **quel que soit l'instant**.
        //
        // Le premier réflexe est d'écarter les voisins proches en temps et en
        // fréquence. Il ne suffit pas : un réseau de Costas décalé de plusieurs
        // symboles retombe encore partiellement juste, et le banc a vu seize
        // fantômes bien séparés nés d'un seul signal. Or deux stations sur la
        // même fréquence dans la même tranche de quinze secondes se brouillent
        // de toute façon ; n'en retenir que la plus forte ne perd rien et rend
        // les trente-deux places à de vraies stations.
        //
        // La tolérance est la **largeur du signal**, pas un nombre choisi au
        // jugé : un candidat décalé d'un seul ton lit encore les tons du vrai
        // signal et son réseau de Costas retombe partiellement juste. Deux
        // candidats dont les bandes se recouvrent sont donc soit le même
        // signal, soit deux signaux que ce démodulateur ne saurait de toute
        // façon pas séparer. Quarante-quatre hertz pour FT8, vingt et un
        // pour FT4 — la formule s'adapte au mode.
        val gardes = ArrayList<Candidat>(maximum)
        for (c in trouves) {
            if (gardes.none { kotlin.math.abs(it.raie - c.raie) <= raieDernierTon }) {
                gardes.add(c)
                if (gardes.size >= maximum) break
            }
        }
        return gardes
    }

    /** Lit les symboles d'un candidat : le ton le plus fort à chaque position. */
    fun tons(spec: Spectrogramme, c: Candidat): IntArray {
        val mode = spec.mode
        val pasParSymbole = mode.parSymbole / spec.pasTemps
        return IntArray(mode.symboles) { s ->
            val t = c.pas + s * pasParSymbole
            var meilleur = 0
            var max = -1f
            for (ton in 0 until mode.tons) {
                val p = spec.puissance(t, c.raie + ton * spec.raieParTon)
                if (p > max) { max = p; meilleur = ton }
            }
            meilleur
        }
    }

    /**
     * La vraisemblance de chaque bit, en logarithme de rapport.
     *
     * Le décodage actuel s'arrête aux décisions dures et au CRC — une erreur et
     * le message est perdu. Ces valeurs douces sont ce qu'attend un décodeur
     * LDPC le jour où il sera écrit : positives pour un zéro probable,
     * négatives pour un un, et d'autant plus grandes que le ton l'emporte
     * nettement. Les produire maintenant coûte dix lignes et évitera de
     * reprendre toute la chaîne ensuite.
     */
    fun vraisemblances(spec: Spectrogramme, c: Candidat): FloatArray {
        val mode = spec.mode
        val bitsParSymbole = when (mode.tons) { 8 -> 3; 4 -> 2; else -> 1 }
        val pasParSymbole = mode.parSymbole / spec.pasTemps
        val positionsDonnees = (0 until mode.symboles)
            .filter { s -> mode.synchro.none { it.first == s } }
        val sortie = FloatArray(positionsDonnees.size * bitsParSymbole)
        var indice = 0
        for (s in positionsDonnees) {
            val t = c.pas + s * pasParSymbole
            // Puissances converties en amplitudes : le logarithme d'une
            // vraisemblance gaussienne est en amplitude, pas en puissance.
            val amp = FloatArray(mode.tons) {
                sqrt(spec.puissance(t, c.raie + it * spec.raieParTon))
            }
            for (b in 0 until bitsParSymbole) {
                var maxZero = 0f
                var maxUn = 0f
                for (ton in 0 until mode.tons) {
                    val valeur = grayInverse(ton, mode.tons)
                    val bit = (valeur shr (bitsParSymbole - 1 - b)) and 1
                    if (bit == 0) maxZero = maxOf(maxZero, amp[ton])
                    else maxUn = maxOf(maxUn, amp[ton])
                }
                sortie[indice++] = ln((maxZero + 1e-9f) / (maxUn + 1e-9f))
            }
        }
        return sortie
    }

    /** Le code de Gray inverse, pour 4 ou 8 tons. */
    private fun grayInverse(ton: Int, tons: Int): Int {
        val table = if (tons == 8) intArrayOf(0, 1, 3, 2, 5, 6, 4, 7)
        else intArrayOf(0, 1, 3, 2)
        return table.indexOf(ton).coerceAtLeast(0)
    }

    // ------------------------------------------------------------- synthèse

    /**
     * Fabrique le signal d'une suite de symboles.
     *
     * Sert d'abord au banc : sans signal connu, on ne peut rien prouver du
     * démodulateur. Mais c'est aussi, tel quel, de quoi **émettre** — une
     * balise FT8 depuis le téléphone ne demanderait plus que de l'envoyer à la
     * carte son.
     *
     * La phase est continue et la trajectoire de fréquence lissée par une
     * gaussienne, comme le fait WSJT-X : sans ce lissage, chaque changement de
     * ton produit des claquements qui s'étalent bien au-delà des 50 Hz du
     * signal, et gênent les voisins de bande.
     */
    fun synthetise(
        tons: IntArray,
        mode: Mode,
        frequenceBasseHz: Double,
        decalageS: Double = 0.0,
        dureeTotaleS: Double = mode.dureeS + 2.0,
        amplitude: Float = 0.5f,
        lissageBT: Double = mode.lissageBT
    ): FloatArray {
        val cadence = mode.cadenceHz
        val total = (dureeTotaleS * cadence).toInt()
        val sortie = FloatArray(total)
        val debut = (decalageS * cadence).roundToInt()

        // Trajectoire de fréquence, un point par échantillon de symbole.
        val nSignal = tons.size * mode.parSymbole
        val freq = DoubleArray(nSignal)
        for (i in 0 until nSignal) {
            val s = i / mode.parSymbole
            freq[i] = frequenceBasseHz + tons[s] * mode.ecartHz
        }
        // Lissage gaussien de la trajectoire.
        if (lissageBT > 0.0) {
            val sigma = mode.parSymbole / (2.0 * PI * lissageBT) * sqrt(ln(2.0))
            val demi = (3 * sigma).toInt().coerceAtLeast(1)
            val noyau = DoubleArray(2 * demi + 1)
            var somme = 0.0
            for (k in noyau.indices) {
                val x = (k - demi).toDouble()
                noyau[k] = exp(-x * x / (2 * sigma * sigma)); somme += noyau[k]
            }
            for (k in noyau.indices) noyau[k] /= somme
            val lisse = DoubleArray(nSignal)
            for (i in 0 until nSignal) {
                var acc = 0.0
                for (k in noyau.indices) {
                    acc += noyau[k] * freq[(i + k - demi).coerceIn(0, nSignal - 1)]
                }
                lisse[i] = acc
            }
            System.arraycopy(lisse, 0, freq, 0, nSignal)
        }

        var phase = 0.0
        for (i in 0 until nSignal) {
            val n = debut + i
            if (n in 0 until total) sortie[n] = (amplitude * sin(phase)).toFloat()
            phase += 2.0 * PI * freq[i] / cadence
            if (phase > 2.0 * PI) phase -= 2.0 * PI
        }
        return sortie
    }

    /**
     * Ajoute un bruit gaussien au rapport signal sur bruit demandé.
     *
     * La référence est celle des modes numériques : la puissance de bruit dans
     * 2500 Hz. C'est ainsi que se lisent les « −21 dB » d'un report FT8, et
     * c'est donc dans cette unité que le banc doit parler.
     */
    fun avecBruit(
        signal: FloatArray,
        mode: Mode,
        rapportDb: Double,
        graine: Long = 1
    ): FloatArray {
        var puissanceSignal = 0.0
        var comptes = 0
        for (v in signal) if (v != 0f) { puissanceSignal += v * v.toDouble(); comptes++ }
        if (comptes == 0) return signal.copyOf()
        puissanceSignal /= comptes
        // Le bruit est réparti sur toute la bande ; le rapport est donné dans
        // 2500 Hz, d'où la mise à l'échelle par la largeur réellement occupée.
        val largeurAnalyse = mode.cadenceHz / 2.0
        val puissanceBruit = puissanceSignal / Math.pow(10.0, rapportDb / 10.0) *
            (largeurAnalyse / 2500.0)
        val ecart = sqrt(puissanceBruit)
        val alea = java.util.Random(graine)
        return FloatArray(signal.size) { i ->
            (signal[i] + ecart * alea.nextGaussian()).toFloat()
        }
    }
}
