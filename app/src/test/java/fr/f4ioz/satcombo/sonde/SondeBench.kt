/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.sonde

import fr.f4ioz.satcombo.sdr.Dsp
import fr.f4ioz.satcombo.sdr.RxChain
import fr.f4ioz.satcombo.sdr.RxMode
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Le banc de mesure de la chaîne radiosonde.
 *
 * La mire répond à « est-ce que c'est branché dans le bon ordre ». Elle a
 * rendu ce service et il n'était pas mince — c'est elle qui a fait tomber le
 * défaut de la M20. Mais elle ne répond pas à la question que se pose
 * réellement l'opérateur devant son écran : à quelle distance ça décroche.
 * Une mire sans bruit se décode toujours ; l'air, jamais.
 *
 * Le projet radiosonde_auto_rx mesure cela d'une manière qu'on peut copier
 * telle quelle : on part d'un signal propre, on lui ajoute un bruit dont on
 * connaît exactement la puissance, et on descend jusqu'à ce que le décodage
 * s'effondre. Le chiffre qui sort est un rapport Eb/N0 en décibels — l'énergie
 * d'un bit divisée par la densité de bruit — et il a l'immense avantage de ne
 * dépendre ni de l'antenne, ni du préampli, ni du dongle : c'est la qualité du
 * démodulateur, et rien d'autre. Leurs valeurs de référence, obtenues sur de
 * vrais enregistrements : RS41 10,2 dB, M10 8,9 dB, DFM 6,9 dB.
 *
 * Ce qu'on fabrique ici n'est pas un modèle simplifié de la chaîne. C'est de
 * l'IQ au format exact de la clé — deux octets non signés par échantillon, à
 * 1 058 400 Hz — versé dans [RxChain], c'est-à-dire dans le récepteur complet
 * de l'application, décimation, filtre de canal, discriminateur et correction
 * continue compris. La quantification sur huit bits du dongle est donc dans la
 * mesure, elle aussi, ce qui est honnête : elle est bien là sur le terrain.
 *
 * Deux balayages sont utiles :
 *
 * 1. Le bruit, à accord parfait, qui donne notre seuil et le situe par rapport
 *    aux chiffres publiés.
 * 2. Le désaccord, à bruit fixe, qui chiffre ce que coûte un poste mal calé.
 *    Le wiki d'auto_rx annonce 2,5 dB perdus à cinq kilohertz de côté sur une
 *    RS41 — plus que tout ce qu'a rapporté le filtrage par modèle de la 18.4.
 *    C'est la mesure qui doit décider du contenu de la 18.5.
 */
object SondeBench {

    /** Débit d'échantillonnage de la clé, celui de la vraie chaîne. */
    const val RATE = Dsp.RTL_RATE

    /**
     * Taille du bloc USB, en octets d'IQ — seize kilo-octets, exactement ce que
     * rend la clé à chaque transfert.
     *
     * Ce détail n'en est pas un. Le concentrateur ne cherche une trame que
     * lorsqu'il a de quoi en contenir une, puis oublie le trop-plein ; la
     * cadence de recherche suit donc la taille des blocs. Verser une seconde
     * entière d'un coup, comme le faisait la première version de ce banc, ne
     * laisse qu'une occasion par seconde et fait mentir la mesure. On découpe
     * donc comme la vraie clé : trois cent quarante et un échantillons de son
     * par bloc, cent vingt-neuf blocs par seconde.
     */
    const val BLOCK = 16 * 1024

    /**
     * Pas de vol demandé à la mire, en secondes réelles.
     *
     * La mire de démonstration comprime deux heures de vol en une minute :
     * chaque trame déplace alors le ballon d'une quarantaine de kilomètres tout
     * en annonçant une seconde d'horloge GPS. Le suivi de vol refuse ces
     * points-là, et il a raison — mais le banc, lui, comptait ces refus comme
     * des échecs de décodage et accusait le récepteur. On demande donc ici un
     * vol honnête : une trame, une seconde, cinq mètres de montée.
     */
    const val STEP_SEC = 1.0

    /**
     * Excursion de la modulation, en hertz.
     *
     * La RS41 module à ±2,4 kHz, les Meteomodem environ deux fois plus large —
     * ce qui explique les quinze kilohertz de filtre d'un côté et les
     * vingt-deux de l'autre.
     */
    fun deviationHz(model: String): Double = when (model) {
        "RS41" -> 2_400.0
        else -> 4_800.0
    }

    /** Un point de mesure. */
    data class Point(
        val model: String,
        val ebn0Db: Double,
        val offsetHz: Double,
        val tuneHz: Double,
        val expected: Int,
        val frames: Int,
        val rejected: Int = 0,
        val swing: Int = 0,
        val perSecond: String = ""
    ) {
        /** Taux d'erreur trame, la grandeur que mesure auto_rx. */
        val per: Double get() =
            if (expected <= 0) 1.0 else (1.0 - frames.toDouble() / expected).coerceIn(0.0, 1.0)

        override fun toString(): String =
            ("%-4s Eb/N0 %5.1f dB  écart %+6.0f Hz  accord %+6.0f Hz  %2d/%2d trames  " +
                "PER %5.1f %%  rejets %d  amplitude %d  [%s]")
                .format(model, ebn0Db, offsetHz, tuneHz, frames, expected, per * 100.0,
                    rejected, swing, perSecond)
    }

    /** La suite de symboles d'une seconde d'émission, alternances de bourrage comprises. */
    private fun slotOf(model: String, p: SondeMire.Point, frameNo: Int, symbols: Int): ByteArray {
        val body = SondeMire.chipsFor(model, p, frameNo)
        val out = ByteArray(maxOf(symbols, body.size))
        System.arraycopy(body, 0, out, 0, body.size)
        for (k in body.size until out.size) out[k] = ((k - body.size) and 1).toByte()
        return out
    }

    /** Quantification sur huit bits non signés, exactement comme le fait la clé. */
    private fun q8(v: Double): Byte =
        (Math.round(v + 127.5).toInt().coerceIn(0, 255)).toByte()

    /**
     * Centre de gravité du signal dans le spectre, en hertz relatifs à
     * l'accord.
     *
     * L'algorithme a été mis au point ici, éprouvé ici, puis versé dans le
     * récepteur : il vit maintenant dans [RxChain.centroidOffsetHz], et le banc
     * se contente de l'appeler. C'est la seule façon de garantir que le chiffre
     * mesuré est bien celui que l'application applique — un banc qui mesure sa
     * propre copie ne mesure rien du tout.
     */
    fun centroidHz(
        chain: RxChain,
        searchHz: Double = 25_000.0,
        thresholdDb: Double = 6.0,
        dcNotchHz: Double = 400.0,
        narrowHz: Double = 4_000.0
    ): Double = chain.centroidOffsetHz(searchHz, thresholdDb, dcNotchHz, narrowHz)

    /**
     * La même mesure, mais sans radio du tout : la mire est versée telle quelle
     * dans le décodeur. C'est le témoin. Tout ce que ce chiffre-là n'atteint
     * pas, ce n'est pas la faute du récepteur.
     */
    fun measureAudio(model: String, seconds: Int = 6): Point {
        SondeHub.stop()
        SondeHub.start(null, SondeMire.RATE, SondeMire.DEMO_FREQ_HZ,
            source = "DEMO", model = model, log = false)
        val src = SondeMire.Source(model, 48.2, -4.5, seconds, stepSec = STEP_SEC)
        val chunk = ShortArray(341)
        val trace = StringBuilder()
        var fed = 0
        var mark = SondeMire.RATE
        while (true) {
            val n = src.read(chunk)
            if (n <= 0) break
            SondeHub.feedLive(chunk, n)
            fed += n
            while (fed >= mark) {
                if (mark > SondeMire.RATE) trace.append(' ')
                trace.append(SondeHub.state.value.frames)
                mark += SondeMire.RATE
            }
        }
        val st = SondeHub.state.value
        SondeHub.stop()
        return Point(model, 99.0, 0.0, 0.0, seconds, st.frames,
            st.rejected, st.swing, trace.toString())
    }

    /**
     * Une mesure complète : on émet [seconds] trames, une par seconde, avec le
     * bruit demandé et le désaccord demandé, et l'on compte ce qui ressort.
     *
     * [tuneHz] est la correction d'accord appliquée dans le logiciel, celle que
     * l'opérateur pose au doigt sur la cascade. [autoTune] la calcule tout seul
     * sur la première seconde reçue : c'est la proposition pour la 18.5, et le
     * banc est là pour dire si elle tient.
     */
    fun measure(
        model: String,
        ebn0Db: Double,
        seconds: Int = 6,
        offsetHz: Double = 0.0,
        tuneHz: Double = 0.0,
        autoTune: Boolean = false,
        seed: Long = 20_260_731L
    ): Point {
        val flight = SondeMire.flight(48.2, -4.5, seconds, stepSec = STEP_SEC)
        val chipRate = SondeMire.chipRate(model)
        val dev = deviationHz(model)
        val baud = SondeModel.byId(model).baud
        require(baud > 0.0) { "le banc veut un modèle précis, pas le mode automatique" }

        // Bruit blanc complexe. La porteuse vaut un en puissance ; la densité de
        // bruit se déduit du rapport voulu et du débit binaire utile.
        val gamma = 10.0.pow(ebn0Db / 10.0)
        val sigma = sqrt(RATE / (2.0 * baud * gamma))
        // On loge signal plus bruit dans la dynamique du convertisseur sans
        // taper les butées : au-delà, la clé écrête et la mesure ne veut plus
        // rien dire.
        val scale = 100.0 / (1.0 + 3.0 * sigma)
        val rnd = Random(seed)

        SondeHub.stop()
        SondeHub.start(null, Dsp.AUDIO_RATE, SondeMire.DEMO_FREQ_HZ,
            source = "DEMO", model = model, log = false)

        val chain = RxChain(maxDeviationHz = dev)
        chain.mode = RxMode.NFM
        chain.bandwidthHz = SondeModel.bandwidthFor(model).toDouble()
        chain.offsetHz = tuneHz

        val n = RATE
        val iq = ByteArray(2 * n)
        val block = ByteArray(BLOCK)
        val audio = ShortArray(chain.maxAudio(BLOCK))
        var phase = 0.0
        var applied = tuneHz
        val trace = StringBuilder()

        for (s in 0 until seconds) {
            val slot = slotOf(model, flight[s], s + 1, Math.round(chipRate).toInt())
            for (k in 0 until n) {
                val idx = (k.toLong() * slot.size / n).toInt().coerceIn(0, slot.size - 1)
                val f = offsetHz + if (slot[idx].toInt() != 0) dev else -dev
                phase += 2.0 * PI * f / RATE
                if (phase > PI) phase -= 2.0 * PI
                if (phase < -PI) phase += 2.0 * PI
                iq[2 * k] = q8((cos(phase) + sigma * rnd.nextGaussian()) * scale)
                iq[2 * k + 1] = q8((sin(phase) + sigma * rnd.nextGaussian()) * scale)
            }
            // Découpage en blocs USB : c'est ce que voit le décodeur en vrai.
            var at = 0
            while (at < iq.size) {
                val len = minOf(BLOCK, iq.size - at)
                System.arraycopy(iq, at, block, 0, len)
                val wantSpectrum = autoTune && s == 0 && at == 0
                val got = chain.process(block, len, audio, feedSpectrum = wantSpectrum)
                SondeHub.feedLive(audio, got)
                at += len
            }
            if (autoTune && s == 0) {
                applied = centroidHz(chain)
                chain.offsetHz = applied
            }
            if (s > 0) trace.append(' ')
            trace.append(SondeHub.state.value.frames)
        }

        val st = SondeHub.state.value
        SondeHub.stop()
        return Point(model, ebn0Db, offsetHz, applied, seconds, st.frames,
            st.rejected, st.swing, trace.toString())
    }
}
