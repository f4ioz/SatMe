/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.sonde

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Le décodage des radiosondes, branché sur la sortie de la chaîne SDR.
 *
 * Le principe est le même que pour la SSTV : la boucle de lecture de la clé
 * appelle [feedLive] avec le son démodulé, et tout le travail se fait ici, hors
 * du fil critique de l'USB. La différence est qu'une radiosonde ne module pas
 * du son mais des bits : ce que l'on reçoit est la sortie brute du
 * discriminateur, et la désaccentuation doit être coupée, sinon les fronts sont
 * arrondis et plus rien ne se décode.
 *
 * Trois décodeurs tournent en parallèle sur le même flux, un par famille :
 * 4800 bauds pour la RS41, 9600 bauds en FSK droite pour la M20, et 9616
 * chips pour le bi-phase de la M10 — soit 4808 bits utiles. Cela coûte un peu
 * de calcul et évite d'avoir à demander à l'opérateur ce qu'il écoute : sur
 * 404,000 MHz au large de Brest, ce sera une M20 ; sur 405,700 une RS41 de
 * Camborne. La machine s'en aperçoit toute seule en une seconde.
 *
 * Il a fallu trois versions pour comprendre pourquoi aucune M20 ne sortait
 * jamais. La M20 et la M10 partageaient un seul démodulateur, réglé sur les
 * demi-bits de la M10, et le flux de la M20 y était donc lu à deux symboles par
 * bit — deux symboles rigoureusement identiques, puisqu'elle ne code rien en
 * demi-bits. Or c'est précisément à cette signature-là que l'ancien décodage
 * dit « Manchester » reconnaissait une mauvaise phase : il voyait des paires
 * plates partout, concluait au bruit, et jetait chaque trame. Le décodeur
 * marchait, les essais passaient, et rien n'arrivait jamais à l'écran. D'où le
 * démodulateur séparé, et l'essai de bout en bout qui va avec.
 *
 * La M10 a demandé une quatrième version. Elle ne se cale pas sur des octets
 * mais sur des chips : on cherche son motif de synchronisation dans le flux de
 * demi-bits, on part de là, et la somme de contrôle dit si c'était le bon
 * endroit. Les huit décalages d'octet et les deux phases de l'ancien code n'ont
 * plus lieu d'être — ils cherchaient un alignement qui n'existait pas.
 *
 * L'opérateur peut nommer le modèle qu'il écoute : les décodeurs inutiles sont
 * alors éteints et la chaîne SDR resserre son filtre sur la bonne largeur, ce
 * qui vaut quelques décibels sur une sonde lointaine.
 */
object SondeHub {

    data class SondeState(
        /** Le décodage tourne. */
        val running: Boolean = false,
        /** Nombre de trames retenues depuis le démarrage. */
        val frames: Int = 0,
        /** Trames rejetées : utile pour juger si l'accord est bon. */
        val rejected: Int = 0,
        /** Dernière trame décodée. */
        val last: SondeFrame? = null,
        /** Numéro de série suivi. */
        val serial: String = "",
        /** Type de la sonde suivie. */
        val type: String = "",
        /** Fichier journal en cours d'écriture. */
        val logFile: String? = null,
        /** Amplitude du signal vue par le discriminateur, 0 à 100. */
        val swing: Int = 0,
        /** D'où vient le son : "SDR", "MIC" ou "USB". */
        val source: String = "SDR",
        /** Modèle écouté, ou "AUTO" quand on les cherche tous. */
        val model: String = SondeModel.AUTO,
        /** Vrai quand la carte son est trop lente pour le modèle choisi. */
        val marginal: Boolean = false
    )

    private val _state = MutableStateFlow(SondeState())
    val state: StateFlow<SondeState> = _state

    /** Le décodage est-il armé ? Testé à chaque bloc, donc volatile. */
    @Volatile
    var active: Boolean = false
        private set

    private var rs41: SondeDemod? = null
    private var m10: SondeDemod? = null
    private var m20: SondeDemod? = null
    private var m10Frame = ByteArray(Meteomodem.M10_LEN)
    private var model: String = SondeModel.AUTO
    private var freqHz = 0L
    private var flight: SondeFlight? = null
    private var logFile: File? = null
    private var frames = 0
    private var rejected = 0
    private var lastUiMs = 0L

    /** Le vol en cours, ou null. */
    val currentFlight: SondeFlight? get() = flight

    /**
     * Arme le décodage.
     *
     * [sampleRate] est celui du flux rendu par la chaîne SDR (44 100 Hz), et
     * [freq] la fréquence écoutée : elle est recopiée dans chaque trame pour
     * qu'on sache, en relisant le journal six mois plus tard, sur quoi la sonde
     * émettait.
     */
    fun start(ctx: Context?, sampleRate: Int, freq: Long, source: String = "SDR",
              model: String = SondeModel.AUTO, log: Boolean = true) {
        freqHz = freq
        this.model = model
        val r = sampleRate.toDouble()
        rs41 = if (SondeModel.wantsRs41(model)) SondeDemod(r, Rs41.BAUD, 2048) else null
        // La M20 est une FSK à deux états : un symbole par bit, et rien de plus.
        m20 = if (SondeModel.wantsM20(model))
            SondeDemod(r, Meteomodem.M20_BAUD, 2048) else null
        // La M10 code en bi-phase à marque : le démodulateur compte les chips,
        // soit 9616 par seconde pour 4808 bits utiles. La 18.6 a corrigé ici un
        // facteur deux qui faisait tourner l'horloge à 19232 et rendait tout
        // décodage M10 impossible.
        m10 = if (SondeModel.wantsM10(model))
            SondeDemod(r, Meteomodem.M10_CHIP_RATE, 2048) else null
        frames = 0
        rejected = 0
        flight = null
        // Le contexte n'est demandé que pour le journal : la démonstration et
        // les essais s'en passent, et peuvent donc faire tourner la chaîne
        // entière hors d'Android.
        logFile = if (log && ctx != null) runCatching { openLog(ctx) }.getOrNull() else null
        active = true
        _state.value = SondeState(running = true, logFile = logFile?.name, source = source,
            model = model, marginal = SondeModel.byId(model).marginal(sampleRate))
    }

    /** Désarme le décodage et ferme le journal. */
    fun stop() {
        active = false
        rs41 = null
        m10 = null
        m20 = null
        _state.value = _state.value.copy(running = false)
    }

    /** Efface le vol suivi sans arrêter le décodage. */
    fun clearFlight() {
        flight = null
        frames = 0
        rejected = 0
        _state.value = _state.value.copy(frames = 0, rejected = 0, last = null,
            serial = "", type = "")
    }

    /**
     * Avale un bloc de son démodulé. Appelé depuis la boucle de lecture de la
     * clé : tout ce qui est fait ici retarde la lecture USB, d'où le tampon
     * borné et l'absence de toute écriture bloquante en dehors du journal.
     */
    fun feedLive(pcm: ShortArray, count: Int) {
        if (!active || count <= 0) return
        val now = System.currentTimeMillis()

        // ------------------------------------------------------------ RS41
        rs41?.let { a ->
            a.feedBits(pcm, count, null)
            if (a.bitsAvailable >= Rs41.LEN_STD * 8 + 64) {
                var found = false
                // L'alignement des octets est inconnu : on essaie les huit
                // décalages possibles, ce qui est bien moins coûteux qu'il n'y
                // paraît puisque la recherche d'en-tête échoue tout de suite sur
                // sept d'entre eux.
                for (off in 0 until 8) {
                    val n = a.packBytes(off)
                    val hit = Rs41.scan(a.bytes, 0, n, freqHz, now) ?: continue
                    accept(hit.frame)
                    found = true
                    break
                }
                if (!found) rejected++
                a.trimTo(Rs41.LEN_STD * 8)
            }
        }

        // -------------------------------------------------------------- M20
        // Deux états, un symbole par bit, poids fort en tête : la trame se lit
        // directement dans les symboles, sans passer par les demi-bits.
        m20?.let { d ->
            d.feedBits(pcm, count, null)
            if (d.bitsAvailable >= Meteomodem.M20_LEN * 8 + 128) {
                val chips = d.chipsCopy()
                for (off in 0 until 8) {
                    val n = d.packBytesMsb(chips, chips.size, off)
                    val hit = Meteomodem.scanM20(d.bytes, 0, n, freqHz, now) ?: continue
                    accept(hit.frame)
                    break
                }
                d.trimTo(Meteomodem.M20_LEN * 8)
            }
        }

        // -------------------------------------------------------------- M10
        // On travaille au niveau des chips : le motif de synchronisation donne
        // le point de départ exact, la somme de contrôle valide le reste. Toutes
        // les occurrences du motif sont essayées, parce qu'un tampon d'une
        // seconde contient une bonne dizaine de rafales.
        m10?.let { b ->
            b.feedBits(pcm, count, null)
            val need = Meteomodem.M10_LEN * 16 + Meteomodem.M10_SYNC.size
            if (b.bitsAvailable >= need + 64) {
                val chips = b.chipsCopy()
                var at = 0
                var got = false
                while (at < chips.size) {
                    val k = Meteomodem.findSync(chips, chips.size, at)
                    if (k < 0) break
                    if (Meteomodem.frameFromChips(chips, k, m10Frame)) {
                        val f = Meteomodem.parseM10(m10Frame, freqHz, now)
                        if (f != null) { accept(f); got = true; break }
                    }
                    at = k + 1
                }
                if (!got) rejected++
                b.trimTo(need)
            }
        }

        if (now - lastUiMs >= 300L) {
            lastUiMs = now
            val any = rs41 ?: m20 ?: m10
            val sw = ((any?.swing(pcm, count) ?: 0) / 328).coerceIn(0, 100)
            _state.value = _state.value.copy(frames = frames, rejected = rejected, swing = sw)
        }
    }

    private fun accept(f: SondeFrame) {
        val fl = flight ?: SondeFlight(
            if (f.serial.isNotBlank()) f.serial else "?", f.type).also { flight = it }
        // Un changement de numéro de série en cours de route, c'est une autre
        // sonde : on repart d'un vol neuf plutôt que de mélanger deux traces.
        if (f.serial.isNotBlank() && fl.serial != "?" && fl.serial != f.serial) {
            flight = SondeFlight(f.serial, f.type)
        }
        val target = flight!!
        if (!target.add(f)) { rejected++; return }
        frames++
        runCatching { appendLog(f) }
        _state.value = _state.value.copy(
            frames = frames, last = f, serial = target.serial, type = target.type)
    }

    // ---------------------------------------------------------------- journal

    /** Dossier des journaux de sondes, créé au besoin. */
    fun logDir(ctx: Context): File =
        File(ctx.filesDir, "sondes").also { if (!it.exists()) it.mkdirs() }

    private fun openLog(ctx: Context): File {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            .format(java.util.Date())
        val f = File(logDir(ctx), "sonde-$stamp.csv")
        if (!f.exists()) f.writeText(SondeExport.csvHeader())
        return f
    }

    private fun appendLog(f: SondeFrame) {
        val file = logFile ?: return
        file.appendText(SondeExport.csvLine(f))
    }

    /** Les journaux enregistrés, du plus récent au plus ancien. */
    fun logs(ctx: Context): List<File> =
        logDir(ctx).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** Écrit la trace du vol en cours au format demandé et rend le fichier. */
    fun export(ctx: Context, kml: Boolean): File? {
        val fl = flight ?: return null
        if (fl.count == 0) return null
        val ext = if (kml) "kml" else "gpx"
        val name = (fl.serial.ifBlank { "sonde" }) + "." + ext
        val out = File(logDir(ctx), name)
        out.writeText(if (kml) SondeExport.kml(fl) else SondeExport.gpx(fl))
        return out
    }
}
