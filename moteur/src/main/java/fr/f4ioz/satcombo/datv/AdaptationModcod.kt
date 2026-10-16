/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import fr.f4ioz.satcombo.datv.s2.Modcod
import java.util.Locale

/**
 * The MODCOD fitted to what our own receiver sees of our signal (the
 * closed loop): the most efficient MODCOD of the operator's range whose
 * QEF threshold stays [AdaptationModcod.Reglage.margeCibleDb] under the
 * measured MER.
 *
 * - Down quickly: the margin under [margeBasseDb] or packets lost, two
 *   seconds running: to the best MODCOD the MER allows, at least one step.
 * - Up slowly: the margin above target + [hysteresisDb] for [monteeMs]
 *   running: to the best MODCOD the MER allows.
 * - After each change, [attenteMs] for the receiver to relock and the
 *   measures to settle: no decision.
 * - The loop lost (our signal no longer seen) for [perteBoucleMs] after it
 *   was seen: the most robust MODCOD of the range, then frozen until seen
 *   again.
 *
 * Only the MODCOD: never the frequency, the symbol rate, the power.
 * Power is only suggested (QO-100, the best MODCOD reached and margin to spare).
 */
class AdaptationModcod(
    val margeBasseDb: Double = 0.5,
    val hysteresisDb: Double = 1.5,
    val monteeMs: Long = 20_000L,
    val perteBoucleMs: Long = 10_000L,
    val attenteMs: Long = 8_000L,
    /** Bad seconds in a row before stepping down. */
    val secondesBaisse: Int = 2,
    /** Margin above the target (dB), at the best MODCOD, from which less power is suggested. */
    val suggestionDb: Double = 6.0,
    /** After a step down, the MODCOD left (and above) barred for this long: no see-saw. */
    val blocageMs: Long = 120_000L,
    val historiqueMax: Int = 20,
) {
    /** One second's view of our signal by the receiver. */
    data class Mesure(
        /** Our own signal held: locked, and the service or the station card in our callsign. */
        val boucle: Boolean,
        val merDb: Double?,
        /** Packets lost (continuity or transport errors). */
        val pertes: Boolean = false,
    )

    data class Changement(val ms: Long, val de: String, val vers: String, val raison: String)

    data class Etat(
        val actif: Boolean = false,
        val boucle: Boolean = false,
        val fige: Boolean = false,
        val courant: String = "",
        /** The MODCOD the MER allows now, or empty. */
        val cible: String = "",
        val margeDb: Double? = null,
        val seuilDb: Double? = null,
        val prochaine: String = "",
        val suggestion: String = "",
    )

    @Volatile var etat = Etat(); private set
    private val historique = ArrayList<Changement>()
    private var vueUneFois = false
    private var derniereBoucle = 0L
    private var dernierChangement = Long.MIN_VALUE / 2
    private var mauvaises = 0
    private var debutMontee = -1L
    private var fige = false
    /** The MODCOD last left going down, barred until [plafondJusqua]. */
    private var plafond: Modcod? = null
    private var plafondJusqua = 0L

    fun historique(): List<Changement> = synchronized(historique) { historique.toList() }

    /**
     * One second: [courant] the MODCOD sent, [min]..[max] the range allowed
     * (by threshold), [margeCibleDb] the margin wanted, [qo100] power may be
     * suggested. The MODCOD to switch to, or null.
     */
    @Synchronized
    fun tic(courant: Modcod, min: Modcod, max: Modcod, margeCibleDb: Double, m: Mesure, t: Long, qo100: Boolean): Modcod? {
        val plage = plage(courant, min, max)
        val robuste = plage.first()
        val base = Etat(actif = true, boucle = m.boucle && m.merDb != null, courant = nom(courant), seuilDb = seuil(courant))
        if (!base.boucle) {
            mauvaises = 0; debutMontee = -1
            if (vueUneFois && t - derniereBoucle > perteBoucleMs && !fige) {
                fige = true
                val r = if (courant != robuste) change(courant, robuste, "boucle perdue depuis plus de %d s : le plus robuste".format(perteBoucleMs / 1000), t) else null
                etat = base.copy(fige = true, courant = nom(r ?: courant), prochaine = "figé sur " + nom(robuste) + " jusqu'au retour de notre signal")
                return r
            }
            etat = base.copy(fige = fige, prochaine = when {
                fige -> "figé sur " + nom(courant) + " jusqu'au retour de notre signal"
                vueUneFois -> "notre signal perdu depuis %d s : le plus robuste après %d s".format((t - derniereBoucle) / 1000, perteBoucleMs / 1000)
                else -> "boucle non confirmée : notre signal n'est pas reçu (service ou carte de visite à notre indicatif)"
            })
            return null
        }
        vueUneFois = true; derniereBoucle = t; fige = false
        val mer = m.merDb!!
        val s = seuil(courant) ?: return null
        val marge = mer - s
        val barre = plafond?.takeIf { t < plafondJusqua }
        val possibles = plage.filter { x -> seuil(x)?.let { it + margeCibleDb <= mer } == true }
        val meilleur = possibles.lastOrNull { barre == null || efficacite(it) < efficacite(barre) } ?: robuste
        val e = base.copy(margeDb = marge, cible = nom(meilleur), suggestion = suggestion(courant, plage, marge, margeCibleDb, qo100))
        val reste = attenteMs - (t - dernierChangement)
        if (reste > 0) {
            mauvaises = 0; debutMontee = -1
            etat = e.copy(prochaine = "attente du raccrochage du récepteur (%d s)".format((reste + 999) / 1000))
            return null
        }
        if (marge < margeBasseDb || m.pertes) {
            debutMontee = -1
            mauvaises++
            val vers = if (efficacite(meilleur) < efficacite(courant)) meilleur else plage.lastOrNull { efficacite(it) < efficacite(courant) }
            val pourquoi = if (m.pertes) "paquets perdus" else "marge %s dB sous %s dB".format(Locale.FRANCE, f1(marge), f1(margeBasseDb))
            if (vers == null) { etat = e.copy(prochaine = pourquoi + " : déjà au plus robuste autorisé"); return null }
            if (mauvaises < secondesBaisse) { etat = e.copy(prochaine = pourquoi + " : descente vers " + nom(vers) + " si cela dure"); return null }
            mauvaises = 0
            plafond = courant; plafondJusqua = t + blocageMs
            val r = change(courant, vers, pourquoi + " (MER %s dB)".format(Locale.FRANCE, f1(mer)), t)
            etat = e.copy(courant = nom(vers), prochaine = "attente du raccrochage du récepteur")
            return r
        }
        mauvaises = 0
        if (marge >= margeCibleDb + hysteresisDb && efficacite(meilleur) > efficacite(courant)) {
            if (debutMontee < 0) debutMontee = t
            val r = monteeMs - (t - debutMontee)
            if (r <= 0) {
                debutMontee = -1
                val c = change(courant, meilleur, "marge %s dB pendant %d s (MER %s dB)".format(Locale.FRANCE, f1(marge), monteeMs / 1000, f1(mer)), t)
                etat = e.copy(courant = nom(meilleur), prochaine = "attente du raccrochage du récepteur")
                return c
            }
            etat = e.copy(prochaine = "montée vers " + nom(meilleur) + " dans %d s si la marge tient".format((r + 999) / 1000))
            return null
        }
        debutMontee = -1
        etat = e.copy(prochaine = when {
            barre != null && marge >= margeCibleDb + hysteresisDb && possibles.any { efficacite(it) >= efficacite(barre) } ->
                "maintien : " + nom(barre) + " écarté encore %d s (pertes récentes)".format((plafondJusqua - t + 999) / 1000)
            marge < margeCibleDb -> "maintien : marge sous la cible, mais au-dessus de %s dB".format(Locale.FRANCE, f1(margeBasseDb))
            efficacite(meilleur) <= efficacite(courant) && courant == plage.last() -> "maintien : le plus efficace autorisé"
            else -> "maintien : montée quand la marge dépasse %s dB".format(Locale.FRANCE, f1(margeCibleDb + hysteresisDb))
        })
        return null
    }

    /** Stops acting (the option unchecked, the transmission ended): the history kept. */
    @Synchronized
    fun arrete() { etat = etat.copy(actif = false, prochaine = "") }

    private fun change(de: Modcod, vers: Modcod, raison: String, t: Long): Modcod {
        dernierChangement = t
        synchronized(historique) {
            historique += Changement(t, nom(de), nom(vers), raison)
            while (historique.size > historiqueMax) historique.removeAt(0)
        }
        return vers
    }

    private fun suggestion(courant: Modcod, plage: List<Modcod>, marge: Double, cible: Double, qo100: Boolean): String {
        if (!qo100 || courant != plage.last() || marge <= cible + suggestionDb) return ""
        return "Marge de %s dB au MODCOD le plus efficace autorisé : la puissance pourrait baisser d'environ %d dB (à décider ; jamais automatique)"
            .format(Locale.FRANCE, f1(marge), (marge - cible - hysteresisDb).toInt())
    }

    companion object {
        fun nom(m: Modcod): String = m.constellation.libelle + " " + m.taux.libelle

        /** The QEF threshold (dB) of [m] in its frame size. */
        fun seuil(m: Modcod): Double? = QualiteDatv.seuilDb(nom(m), 4, m.courte)

        /** Transport stream bits per symbol. */
        fun efficacite(m: Modcod): Double = m.dfl.toDouble() / m.symboles

        /**
         * The MODCODs allowed, least efficient first: those this transmitter
         * makes in [courant]'s frame whose threshold lies between [min]'s and [max]'s.
         */
        fun plage(courant: Modcod, min: Modcod, max: Modcod): List<Modcod> {
            val a = seuil(min.copy(courte = courant.courte)) ?: Double.NEGATIVE_INFINITY
            val b = seuil(max.copy(courte = courant.courte)) ?: Double.POSITIVE_INFINITY
            val (lo, hi) = if (a <= b) a to b else b to a
            val l = Modcod.tous(courant.courte, courant.pilotes).filter { m -> seuil(m)?.let { it in lo..hi } == true }.sortedBy { efficacite(it) }
            return l.ifEmpty { listOf(courant) }
        }

        private fun f1(x: Double) = "%.1f".format(Locale.FRANCE, x)
    }
}
