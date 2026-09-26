/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.ft8

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import fr.f4ioz.satcombo.domain.Ft8Decodeur
import fr.f4ioz.satcombo.domain.Ft8Signal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * L'écoute FT8 et FT4 : le micro, les tranches, le décodage.
 *
 * **Le découpage en tranches est tout le problème.** FT8 émet dans des fenêtres
 * de quinze secondes calées sur l'heure UTC, FT4 dans des fenêtres de sept et
 * demie. Une transmission commence une demi-seconde après le début de sa
 * fenêtre et dure 12,64 s pour FT8, 5,04 s pour FT4. Le récepteur doit donc
 * savoir *quand* il est, à mieux qu'une seconde près — ce qui repose entièrement
 * sur l'horloge du téléphone.
 *
 * Un concentrateur unique et non un état par écran : l'écoute doit survivre au
 * passage dans le carnet ou dans les réglages. Perdre quinze secondes parce
 * qu'on a consulté autre chose serait perdre un cycle entier.
 */
object Ft8Hub {

    /** Ce que l'écran observe. */
    data class Etat(
        val enMarche: Boolean = false,
        val mode: String = "FT8",
        /** Ce qui a été entendu, le plus récent en tête. */
        val entendus: List<Entendu> = emptyList(),
        /** Seconde courante dans la tranche, pour la barre de progression. */
        val avancement: Float = 0f,
        /** Niveau d'entrée, pour savoir si le micro reçoit quelque chose. */
        val niveau: Float = 0f,
        /** Combien de tranches analysées depuis le départ. */
        val tranches: Int = 0,
        /**
         * La cascade : une ligne par pas d'analyse, la plus récente en tête,
         * chaque octet étant un niveau de 0 à 255 sur la bande explorée.
         *
         * Des octets et non des flottants : une cascade de trente lignes sur
         * deux cent cinquante-six colonnes tient ainsi dans huit kilooctets, et
         * l'écran n'a de toute façon pas plus de nuances à montrer.
         */
        val cascade: List<ByteArray> = emptyList(),

        /**
         * Le spectre de l'instant, une valeur par colonne, de 0 à 1.
         *
         * Il répond à une autre question que la cascade. La cascade montre
         * l'histoire — qui a émis, quand, pendant combien de temps. Le spectre
         * montre le présent, et c'est lui qu'on regarde en tournant le bouton
         * d'accord ou en cherchant si le poste sort quelque chose.
         */
        val spectre: FloatArray = FloatArray(0),
        /** Bornes de la bande affichée, pour graduer l'axe. */
        val basseHz: Int = 200,
        val hauteHz: Int = 3000,
        /** Vide quand tout va bien. */
        val panne: String = ""
    )

    data class Entendu(
        val heure: String,
        val texte: String,
        val frequenceHz: Int,
        val rapportDb: Int,
        val decalageS: Double,
        val mode: String
    )

    private val _state = MutableStateFlow(Etat())
    val state = _state.asStateFlow()

    private var boucle: Job? = null
    private const val CADENCE = 12000

    /** Les deux modes, avec la durée de leur tranche. */
    private fun modeDe(nom: String) =
        if (nom == "FT4") Ft8Signal.FT4 else Ft8Signal.FT8

    private fun trancheS(nom: String) = if (nom == "FT4") 7.5 else 15.0

    fun demarre(ctx: Context, nom: String) {
        arrete()
        _state.value = Etat(enMarche = true, mode = nom)
        boucle = CoroutineScope(Dispatchers.Default).launch { tourne(nom, this) }
    }

    fun arrete() {
        boucle?.cancel()
        boucle = null
        _state.value = _state.value.copy(enMarche = false, avancement = 0f)
    }

    /**
     * Change de mode.
     *
     * En pleine écoute, cela relance : les tranches n'ont pas la même durée, et
     * chercher du FT4 dans une fenêtre de quinze secondes ne donnerait rien.
     */
    fun choisitMode(ctx: Context, nom: String) {
        if (nom == _state.value.mode) return
        val tournait = _state.value.enMarche
        arrete()
        _state.value = _state.value.copy(mode = nom, panne = "", avancement = 0f)
        if (tournait) demarre(ctx, nom)
    }

    fun vide() {
        _state.value = _state.value.copy(
            entendus = emptyList(), tranches = 0, cascade = emptyList())
    }

    private fun panne(quoi: String) {
        // Une écoute qui ne décode rien et une écoute qui n'a jamais démarré se
        // ressemblent à l'écran : le micro refusé, l'appareil occupé par une
        // autre application, une cadence non accordée. Chacune a son mot.
        _state.value = _state.value.copy(enMarche = false, panne = quoi)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun tourne(nom: String, portee: CoroutineScope) {
        val mode = modeDe(nom)
        val tranche = trancheS(nom)

        val min = AudioRecord.getMinBufferSize(
            CADENCE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) { panne("cadence"); return }

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, CADENCE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 8)
        } catch (e: SecurityException) { panne("permission"); return }
        catch (e: Exception) { panne("micro"); return }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); panne("micro"); return
        }
        try { rec.startRecording() } catch (e: Exception) {
            rec.release(); panne("occupe"); return
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release(); panne("occupe"); return
        }

        val parTranche = (tranche * CADENCE).toInt()
        // Des blocs courts : à 4096 échantillons, soit un tiers de seconde, la
        // cascade avancerait par saccades de trois lignes d'un coup.
        val bloc = ShortArray(1024)
        val analyseur = Analyseur(BASSE_HZ, HAUTE_HZ, COLONNES)
        var tampon = FloatArray(parTranche)
        var ecrit = 0
        // On jette la première tranche : elle commence au milieu de nulle part
        // et ne contiendrait qu'un morceau de transmission.
        var premiere = true
        var trancheCourante = numeroTranche(tranche)

        try {
            while (portee.isActive) {
                val lus = rec.read(bloc, 0, bloc.size)
                if (lus <= 0) { delay(20); continue }

                var pic = 0f
                for (i in 0 until lus) {
                    val v = bloc[i] / 32768f
                    if (ecrit < parTranche) tampon[ecrit++] = v
                    val a = kotlin.math.abs(v)
                    if (a > pic) pic = a
                }

                val n = numeroTranche(tranche)
                if (n != trancheCourante) {
                    val pleine = tampon
                    val combien = ecrit
                    trancheCourante = n
                    tampon = FloatArray(parTranche)
                    ecrit = 0
                    if (premiere) {
                        premiere = false
                    } else if (combien > mode.parSymbole * mode.symboles / 4) {
                        decodeTranche(pleine.copyOf(combien), mode, nom)
                    }
                }

                val nouvelleLigne = analyseur.verse(bloc, lus)
                _state.value = _state.value.copy(
                    niveau = pic,
                    avancement = (ecrit.toFloat() / parTranche).coerceIn(0f, 1f),
                    cascade = if (nouvelleLigne) analyseur.lignes.toList()
                              else _state.value.cascade,
                    spectre = if (nouvelleLigne) analyseur.dernierSpectre
                              else _state.value.spectre)
            }
        } catch (e: Exception) {
            panne("lecture")
        } finally {
            try { rec.stop() } catch (_: Exception) { }
            try { rec.release() } catch (_: Exception) { }
        }
    }

    /** Le numéro de la tranche courante, depuis l'époque UTC. */
    private fun numeroTranche(dureeS: Double): Long =
        (System.currentTimeMillis() / (dureeS * 1000).toLong())

    private const val COLONNES = 256
    /** La bande explorée, en hertz — celle des états et de l'analyseur. */
    private const val BASSE_HZ = 200
    private const val HAUTE_HZ = 3000
    private const val LIGNES_CASCADE = 60

    /**
     * L'analyseur en direct.
     *
     * **Pourquoi il est séparé du décodage.** La cascade était fabriquée à la
     * fin de chaque tranche, à partir du spectrogramme du décodeur : elle
     * n'avançait donc que toutes les quinze secondes, et pas du tout quand une
     * tranche échouait. Or c'est précisément en accordant le poste — quand
     * rien ne décode encore — qu'on a le plus besoin de voir la bande.
     *
     * Il tourne donc sur le flux d'entrée, sans rien attendre du décodeur, et
     * continue de couler même si aucune tranche ne donne quoi que ce soit.
     *
     * La fenêtre fait 2048 points à 12 000 Hz, soit 171 ms et 5,9 Hz par raie.
     * Un signal FT8 occupe huit raies : assez pour se distinguer nettement,
     * sans la finesse inutile qui coûterait du calcul à chaque image.
     */
    private const val FENETRE = 2048
    /** Un huitième de seconde entre deux lignes : l'œil n'en demande pas plus. */
    private const val PAS_ANALYSE = 1536

    private class Analyseur(val basseHz: Int, val hauteHz: Int, val colonnes: Int) {
        private val anneau = FloatArray(FENETRE)
        private var ecrit = 0
        private var depuisDerniere = 0
        private val re = FloatArray(FENETRE)
        private val im = FloatArray(FENETRE)
        /** La fenêtre de Hann, calculée une fois. */
        private val fenetre = FloatArray(FENETRE) {
            (0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * it / (FENETRE - 1))).toFloat()
        }
        /** Le plancher de bruit, lissé — sans quoi l'image clignoterait. */
        private var plancher = -1.0

        val lignes = ArrayDeque<ByteArray>()
        var dernierSpectre = FloatArray(colonnes)
            private set

        /** Verse des échantillons ; rend vrai quand une nouvelle ligne est prête. */
        fun verse(bloc: ShortArray, combien: Int): Boolean {
            var pret = false
            for (i in 0 until combien) {
                anneau[ecrit] = bloc[i] / 32768f
                ecrit = (ecrit + 1) % FENETRE
                if (++depuisDerniere >= PAS_ANALYSE) { depuisDerniere = 0; pret = analyse() }
            }
            return pret
        }

        private fun analyse(): Boolean {
            // L'anneau est déroulé du plus ancien au plus récent, fenêtré au
            // passage : sans fenêtre, chaque ton déborderait sur ses voisins et
            // la cascade serait une bouillie.
            for (k in 0 until FENETRE) {
                re[k] = anneau[(ecrit + k) % FENETRE] * fenetre[k]
                im[k] = 0f
            }
            Ft8Signal.fft(re, im)

            val binHz = CADENCE.toDouble() / FENETRE
            val r0 = (basseHz / binHz).toInt().coerceAtLeast(1)
            val r1 = (hauteHz / binHz).toInt().coerceAtMost(FENETRE / 2 - 1)
            if (r1 <= r0) return false

            var somme = 0.0
            val ligne = ByteArray(colonnes)
            val courbe = FloatArray(colonnes)
            for (c in 0 until colonnes) {
                val a = r0 + c * (r1 - r0) / colonnes
                val b = (r0 + (c + 1) * (r1 - r0) / colonnes).coerceAtLeast(a + 1)
                // Par maximum et non par moyenne : une station étroite et forte
                // se perdrait dans une moyenne avec le bruit qui l'entoure, et
                // c'est justement elle qu'on cherche.
                var pic = 0f
                for (r in a until minOf(b, r1)) {
                    val v = re[r] * re[r] + im[r] * im[r]
                    if (v > pic) pic = v
                    somme += v
                }
                courbe[c] = pic
            }
            val moyenne = somme / (r1 - r0).coerceAtLeast(1)
            plancher = if (plancher < 0) moyenne else plancher * 0.9 + moyenne * 0.1
            val ref = if (plancher <= 0.0) 1e-12 else plancher

            for (c in 0 until colonnes) {
                val db = 10.0 * kotlin.math.log10((courbe[c] + 1e-12) / ref)
                val v = ((db / 30.0).coerceIn(0.0, 1.0) * 255).toInt()
                ligne[c] = v.toByte()
                courbe[c] = (db / 30.0).coerceIn(0.0, 1.0).toFloat()
            }
            dernierSpectre = courbe
            lignes.addFirst(ligne)
            while (lignes.size > LIGNES_CASCADE) lignes.removeLast()
            return true
        }
    }

    // La cascade par tranche a été retirée ici : elle répondait à la même
    // question que l'analyseur en direct, et moins bien — une image toutes les
    // quinze secondes, et aucune quand la tranche ne décodait pas. Garder les
    // deux aurait donné deux vérités qui se contredisent à l'écran.

    private fun decodeTranche(audio: FloatArray, mode: Ft8Signal.Mode, nom: String) {
        val entendus = try {
            Ft8Decodeur.decode(audio, CADENCE.toDouble(), mode)
        } catch (e: Exception) {
            emptyList()
        }
        val heure = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())

        val nouveaux = entendus.map {
            Entendu(
                heure = heure,
                texte = it.message.brut,
                frequenceHz = Math.round(it.frequenceHz).toInt(),
                rapportDb = it.rapportDb,
                decalageS = it.instantS,
                mode = nom
            )
        }
        val avant = _state.value
        _state.value = avant.copy(
            // Les plus récents en tête, et on borne la liste : une soirée
            // d'écoute ferait autrement des milliers de lignes en mémoire.
            entendus = (nouveaux + avant.entendus).take(300),
            tranches = avant.tranches + 1)
    }
}
