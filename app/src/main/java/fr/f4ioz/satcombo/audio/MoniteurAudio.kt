/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Ce que l'écran montre du son en cours d'enregistrement. */
data class EtatMoniteur(
    val actif: Boolean = false,
    val bandes: List<Float> = emptyList(),
    val crete: Float = 0f,
    /** Vrai quand le son part réellement vers le haut-parleur. */
    val hautParleur: Boolean = false
)

/**
 * Le contrôle à l'oreille et à l'œil de ce qui s'enregistre.
 *
 * Deux services rendus par le même robinet : le spectre du son capté, et le
 * renvoi de ce son vers le haut-parleur du téléphone. Les deux se branchent sur
 * la prise déjà posée par [PassRecorder] pour le décodeur SSTV — les
 * échantillons bruts, avant l'encodeur MP3.
 *
 * Toute la difficulté tient en une phrase du magnétophone : la prise de son
 * *doit rendre la main tout de suite*, sinon le tampon de l'AudioRecord
 * déborde et l'enregistrement se retrouve troué. On ne calcule donc rien et on
 * n'écrit rien sur le fil de capture : [alimenter] recopie le bloc dans une
 * petite file et repart. Un fil à nous fait la transformée de Fourier et écrit
 * dans l'AudioTrack, dont l'écriture, elle, a tout le droit de bloquer.
 *
 * Quand la file est pleine, on jette. C'est le bon arbitrage : un moniteur qui
 * hoquette se remarque à peine, un enregistrement troué est perdu pour de bon.
 *
 * Le haut-parleur ne s'ouvre que sur une source extérieure — carte son USB du
 * poste, ou liaison Bluetooth. Renvoyer le micro du téléphone dans le
 * haut-parleur du même téléphone ne donnerait qu'un effet Larsen, et n'aurait
 * de toute façon aucun intérêt puisque le son est déjà dans la pièce.
 */
object MoniteurAudio {

    private val _etat = MutableStateFlow(EtatMoniteur())
    val etat: StateFlow<EtatMoniteur> = _etat

    /** Calculer et publier le spectre. Se change en cours d'enregistrement. */
    @Volatile var spectre = false
    /** Renvoyer le son au haut-parleur. Se change en cours d'enregistrement. */
    @Volatile var hautParleur = false

    /** Vrai si la prise de son ne vient pas du micro du téléphone. */
    @Volatile private var sourceExterne = false
    @Volatile private var enMarche = false
    private var fil: Thread? = null
    /** La cadence de la capture en cours, lisible par qui en a besoin. */
    @Volatile var cadence: Int = 44_100
        private set

    private var rate = 44_100

    /** Huit blocs : environ un dixième de seconde d'avance, pas davantage —
     *  au-delà, le contrôle à l'oreille arriverait après le son du poste. */
    private val file = ArrayBlockingQueue<ShortArray>(8)

    /** Le haut-parleur est-il utilisable avec la source en cours ? */
    fun hautParleurPossible(): Boolean = sourceExterne

    /**
     * Ouvre le moniteur pour la durée d'un enregistrement. Appelé même quand
     * les deux options sont fermées : le fil dort alors sans rien consommer, et
     * l'opérateur peut allumer le spectre ou le haut-parleur en plein passage.
     */
    fun demarrer(rate: Int, sourceExterne: Boolean, spectre: Boolean, hautParleur: Boolean) {
        cadence = rate
        arreter()
        this.rate = rate
        this.sourceExterne = sourceExterne
        this.spectre = spectre
        this.hautParleur = hautParleur
        file.clear()
        enMarche = true
        _etat.value = EtatMoniteur(actif = true)
        fil = thread(name = "MoniteurAudio", isDaemon = true) { boucle() }
    }

    /** Recopie [n] échantillons dans la file. Appelé sur le fil de capture :
     *  ne doit jamais bloquer ni calculer quoi que ce soit. */
    fun alimenter(pcm: ShortArray, n: Int) {
        // La démonstration se sert au passage, avant toute autre condition :
        // elle doit recevoir le son même quand ni le spectre ni le haut-parleur
        // ne sont demandés. L'encodage est fait là-bas, et n'y bloque pas.
        fr.f4ioz.satcombo.demo.ServeurDemo.verseAudio(pcm, n, cadence)
        if (!enMarche) return
        if (!spectre && !(hautParleur && sourceExterne)) return
        if (n <= 0) return
        file.offer(pcm.copyOf(n))
    }

    fun arreter() {
        if (!enMarche && fil == null) { _etat.value = EtatMoniteur(); return }
        enMarche = false
        runCatching { fil?.join(1_500) }
        fil = null
        file.clear()
        _etat.value = EtatMoniteur()
    }

    private fun boucle() {
        val analyseur = AnalyseurSpectre(rate = rate)
        var piste: AudioTrack? = null
        var dernierePublication = 0L
        try {
            while (enMarche) {
                val bloc = file.poll(200, TimeUnit.MILLISECONDS)
                val veutHp = hautParleur && sourceExterne
                if (!veutHp && piste != null) { fermerPiste(piste); piste = null }
                if (bloc == null) continue

                if (spectre && analyseur.pousser(bloc, bloc.size)) {
                    // Une fenêtre de mille points à 44 100 Hz revient toutes les
                    // vingt-trois millisecondes : republier à ce rythme ferait
                    // redessiner l'écran quarante fois par seconde pour un œil
                    // qui n'en demande pas tant.
                    val t = System.currentTimeMillis()
                    if (t - dernierePublication >= 60) {
                        dernierePublication = t
                        _etat.value = EtatMoniteur(
                            actif = true,
                            bandes = analyseur.bandes.toList(),
                            crete = analyseur.crete,
                            hautParleur = veutHp)
                    }
                }

                if (veutHp) {
                    if (piste == null) piste = runCatching { ouvrirPiste() }.getOrNull()
                    // L'écriture bloque quand le tampon est plein : c'est elle
                    // qui cadence le fil, et c'est très bien ainsi.
                    piste?.let { runCatching { it.write(bloc, 0, bloc.size) } }
                }
            }
        } finally {
            piste?.let { fermerPiste(it) }
        }
    }

    private fun ouvrirPiste(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // Un demi-seconde de marge : le fil partage le processeur avec
        // l'encodeur MP3 et, le cas échéant, avec le décodeur SSTV.
        val taille = maxOf(min * 2, rate)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
            .setBufferSizeInBytes(taille)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        runCatching { t.setVolume(AudioTrack.getMaxVolume()) }
        // **Forcer le haut-parleur du téléphone.**
        //
        // Dès qu'une carte audio USB est branchée, Android y dirige la sortie
        // média : le moniteur jouait dans la carte, dont la sortie n'est
        // souvent reliée à rien. Le spectre s'affichait — la capture, elle,
        // fonctionnait — mais le téléphone restait muet, et l'option « écouter
        // au haut-parleur » semblait sans effet.
        //
        // C'est exactement le cas où l'on veut **l'inverse** du routage par
        // défaut : la source vient de la carte, l'écoute doit rester sur le
        // téléphone. On le dit donc explicitement.
        forceHautParleur(t)
        t.play()
        return t
    }

    /** Le contexte, posé au démarrage : sans lui, pas d'accès au routage. */
    @Volatile var contexte: android.content.Context? = null

    private fun forceHautParleur(t: AudioTrack) {
        val ctx = contexte ?: return
        runCatching {
            val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE)
                as android.media.AudioManager
            val hp = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull {
                    it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
            // Un échec n'est pas fatal : sans carte USB branchée, le routage
            // par défaut était déjà le bon.
            if (hp != null) t.preferredDevice = hp
        }
    }

    private fun fermerPiste(t: AudioTrack) {
        runCatching { t.pause(); t.flush(); t.stop() }
        runCatching { t.release() }
    }
}
