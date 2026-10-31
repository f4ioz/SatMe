/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * « Débruitage IA automatique » (SSTV › Réglages › Couleurs et IA, off by
 * default): each picture received live goes through the learnt denoiser
 * ([DebruiteurIa]) by itself — only when the model is installed, and, when
 * asked, only when its noise ([RetouchesGalerie.bruit], the luma's σ in
 * levels) is at least [Regle.seuil]: a clean picture is left alone.
 *
 * In the background, one picture at a time, on a low-priority thread
 * ([RedecodageAuto.filBas]): the decoder, the auto-pilot and the next
 * picture never wait for it. It works on the picture as received (what the
 * viewer shows first and the web site takes), its colour as the defaults
 * say, and marks the picture ([RetouchesGalerie.Reglage.iaAuto]): from then
 * on it is shown « IA » wherever it goes, like one asked by hand. A picture
 * with a switch of its own in the viewer (IA on or off) is not touched. The
 * picture as received is never changed.
 *
 * The version the viewer shows first — the one decoded again at the end
 * ([corrigee], `corrigees/`), when there is one — gets its IA too: made with
 * the other when it is already there, else once it comes
 * ([apresCorrection]); the owner never has to switch the IA off and on.
 *
 * [fini] hears each picture's outcome (the site sends the new version).
 */
class IaAuto(
    private val retouches: RetouchesGalerie,
    private val regle: () -> Regle,
    private val lance: (Runnable) -> Unit = RedecodageAuto.filBas(nom = "sstv-ia-auto"),
    private val corrigee: (galerie: File, png: String) -> File? = { _, _ -> null },
    private val fini: (png: String, issue: String) -> Unit = { _, _ -> },
) {
    /**
     * The owner's choice: [actif] the switch, [seuilActif] only pictures at
     * least [seuil] noisy (luma σ, levels; [SEUIL_MIN] to [SEUIL_MAX]).
     */
    data class Regle(val actif: Boolean = false, val seuilActif: Boolean = true, val seuil: Double = SEUIL_DEFAUT) {
        fun borne() = copy(seuil = seuil.coerceIn(SEUIL_MIN, SEUIL_MAX))
    }

    private val attente = AtomicInteger(0)

    /** Pictures handed over and not done yet. */
    val enAttente: Int get() = attente.get()

    /** The last picture's outcome: its name, [FAITE] or why not, its noise (-1 unknown). */
    @Volatile var dernier: Triple<String, String, Double> = Triple("", "", -1.0); private set

    /**
     * Picture [png] of gallery [galerie] just received: queued (true), or not
     * (switched off, no model, the queue full). Returns at once.
     */
    fun demande(galerie: File, png: String): Boolean {
        val r = runCatching(regle).getOrDefault(Regle())
        if (!r.actif || retouches.ia == null) return false
        return file {
                    val i = runCatching { traite(galerie, png) }.getOrElse { Triple(it.javaClass.simpleName, -1.0, null) }
                    dernier = Triple(png, i.first, i.second)
                    runCatching { fini(png, i.first) }
        }
    }

    /**
     * Picture [png] decoded again at the end, a better version kept: when its
     * IA is on (by itself or asked), that version gets it too — on the same
     * thread, after the picture's own turn. Returns at once.
     */
    fun apresCorrection(galerie: File, png: String): Boolean {
        if (retouches.ia == null) return false
        return file { runCatching { iaCorrigee(galerie, png) } }
    }

    private fun file(travail: () -> Unit): Boolean {
        if (attente.get() >= RedecodageAuto.MAX_ATTENTE) return false
        attente.incrementAndGet()
        try {
            lance(Runnable { try { travail() } finally { attente.decrementAndGet() } })
        } catch (e: Exception) { attente.decrementAndGet(); return false }
        return true
    }

    /** The version decoded again of [png], its IA made when the picture wants it and it has none yet. */
    private fun iaCorrigee(galerie: File, png: String): RetouchesGalerie.Finie? {
        val c = runCatching { corrigee(galerie, png) }.getOrNull()?.takeIf { it.isFile } ?: return null
        val e = retouches.effet(galerie, png)
        if (!e.ia || retouches.ia == null) return null
        return retouches.existante(galerie, c, e) ?: retouches.finie(galerie, c, e)
    }

    /** Picture [png] measured and, when it should, denoised: the outcome, its noise, the version made. */
    fun traite(galerie: File, png: String): Triple<String, Double, RetouchesGalerie.Finie?> {
        val base = File(galerie, png)
        if (!base.isFile) return Triple(ILLISIBLE, -1.0, null)
        val avant = retouches.reglage(galerie, png)
        // Its own switch in the viewer: the owner's, not ours.
        if (avant.ia != null) return Triple(CHOISIE, -1.0, null)
        val bruit = if (runCatching(regle).getOrDefault(Regle()).seuilActif) retouches.bruit(base) ?: return Triple(ILLISIBLE, -1.0, null) else -1.0
        val d = decide(runCatching(regle).getOrDefault(Regle()), retouches.ia != null, bruit)
        if (d != FAITE) return Triple(d, bruit, null)
        val apres = avant.copy(iaAuto = true)
        val e = retouches.effet(apres)
        val f = retouches.finie(galerie, base, e) ?: return Triple(ECHEC, bruit, null)
        // Marked once the version is there: never « IA » without its picture.
        if (!retouches.change(galerie, png, apres)) return Triple(ECHEC, bruit, f)
        runCatching { iaCorrigee(galerie, png) }
        return Triple(FAITE, bruit, f)
    }

    companion object {
        const val FAITE = "faite"
        /** Switched off before its turn. */
        const val COUPE = "coupe"
        const val SANS_MODELE = "sans_modele"
        /** Under the noise threshold: left as it is. */
        const val PROPRE = "propre"
        /** The picture has its own IA switch. */
        const val CHOISIE = "choisie"
        const val ILLISIBLE = "illisible"
        const val ECHEC = "echec"

        /**
         * About 0.85 × the σ of a white noise added. On the owner's 215
         * pictures (median 4.0): under 5 their mean mark is 80 to 85, from 5
         * on 65 — the noisy ones; snow beyond 28.
         */
        const val SEUIL_DEFAUT = 5.0
        const val SEUIL_MIN = 0.0
        const val SEUIL_MAX = 30.0

        /**
         * The rule: [FAITE] when switched on, the model there ([modele]) and
         * — the threshold asked — [bruit] at least the threshold; else why not.
         */
        fun decide(r: Regle, modele: Boolean, bruit: Double): String {
            val b = r.borne()
            return when {
                !b.actif -> COUPE
                !modele -> SANS_MODELE
                b.seuilActif && bruit < b.seuil -> PROPRE
                else -> FAITE
            }
        }
    }
}
