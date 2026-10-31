/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.domain.Astres
import org.json.JSONObject
import java.util.Locale

/**
 * « Soleil » / « Lune » in the rotor card: the rotor pointed once at the
 * Sun or the Moon as seen now, and with « suivre » again every [periodeMs]
 * until stopped — for aligning a dish on the Sun's noise.
 *
 * **Only on a click.** Nothing here runs by itself: no thread before
 * [vise], called only by the page's POST; the follow ends on « Arrêter »,
 * the rotor's Stop, Park, « Aller », the satellite followed again, a
 * disconnection ([arrete]), or once the body sets. The rotor's own limits
 * apply ([vers]: StationRotor.vers, which refuses — never clamps — what is
 * out of reach, and stops the satellite tracking).
 */
class AstreRotorPc(
    private val observateur: () -> Observer?,
    /** The rotor connected. */
    private val connecte: () -> Boolean,
    /** The lowest elevation the rotor is set to (Réglages › Rotor), the horizon at least. */
    private val elMin: () -> Double,
    /** Points the rotor, true when sent (StationRotor.vers). */
    private val vers: (Double, Double) -> Boolean,
    private val horloge: () -> Long = System::currentTimeMillis,
    private val periodeMs: Long = 20_000L,
) {
    @Volatile var astre: Astres.Astre? = null; private set
    @Volatile var suit = false; private set
    @Volatile var message = ""; private set
    @Volatile var dernier: Pair<Double, Double>? = null; private set
    @Volatile var envois = 0; private set
    @Volatile private var fil: Thread? = null

    private fun f(x: Double) = String.format(Locale.FRANCE, "%.1f", x)

    /** Where [a] is now, or null when the station's position is not known. */
    fun position(a: Astres.Astre, t: Long = horloge()): Pair<Double, Double>? =
        observateur()?.let { Astres.azEl(a, it.latDeg, it.lonDeg, it.altMeters, t) }

    /** One pointing: the refusal's words, or null when sent. */
    private fun pointe(a: Astres.Astre): String? {
        if (!connecte()) return "aucun rotor connecté"
        val (az, el) = position(a) ?: return "la position de la station n'est pas connue"
        if (el < 0.0) return "${a.nom} sous l'horizon (${f(el)}°)"
        if (el < elMin()) return "${a.nom} trop bas pour le rotor (${f(el)}°, élévation min ${f(elMin())}°)"
        if (!vers(az, el)) return "refusé : hors des limites du rotor"
        dernier = az to el
        envois++
        message = "Rotor vers le ${a.nom} : az ${f(az)}°, él ${f(el)}°" + if (suit) " — suivi, toutes les ${periodeMs / 1000} s" else ""
        return null
    }

    /**
     * The page's click: [cle] « soleil » or « lune », [suivre] to keep on it.
     * Refused with its reason when the body is below the horizon, the rotor
     * not connected or the direction out of reach.
     */
    @Synchronized fun vise(cle: String?, suivre: Boolean): JSONObject {
        val a = Astres.astre(cle) ?: return JSONObject().put("ok", false).put("erreur", "astre inconnu : Soleil ou Lune")
        arrete("")
        astre = a; suit = suivre
        val refus = pointe(a)
        if (refus != null) { suit = false; message = refus; return JSONObject().put("ok", false).put("erreur", refus) }
        if (suivre) {
            val th = Thread {
                try {
                    while (suit && astre == a && fil === Thread.currentThread()) {
                        Thread.sleep(periodeMs)
                        if (!(suit && astre == a && fil === Thread.currentThread())) break
                        pointe(a)?.let { r -> message = "Suivi du ${a.nom} arrêté : $r"; suit = false }
                    }
                } catch (_: InterruptedException) {}
            }.apply { isDaemon = true; name = "rotor-" + a.cle }
            fil = th
            th.start()
        }
        return JSONObject().put("ok", true).put("message", message)
    }

    /** Stops the follow ([raison]: why, shown; "" keeps the message). Never moves the rotor. */
    @Synchronized fun arrete(raison: String = "arrêté") {
        val etait = suit
        suit = false
        fil?.interrupt(); fil = null
        if (etait && raison.isNotEmpty()) message = "Suivi du ${astre?.nom ?: "?"} $raison."
    }

    /** The card's line: where each body is now, what was sent, the follow. */
    fun json(): JSONObject {
        val o = JSONObject().put("suit", suit).put("astre", astre?.cle ?: "").put("message", message)
        for (a in Astres.Astre.values()) position(a)?.let { (az, el) ->
            o.put(a.cle, JSONObject().put("az", Math.round(az * 10) / 10.0).put("el", Math.round(el * 10) / 10.0).put("leve", el >= 0.0))
        }
        dernier?.let { (az, el) -> o.put("dernier", JSONObject().put("az", Math.round(az * 10) / 10.0).put("el", Math.round(el * 10) / 10.0)) }
        return o
    }
}
