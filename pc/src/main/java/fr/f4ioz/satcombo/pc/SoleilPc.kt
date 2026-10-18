/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.demo.ReponseWeb
import fr.f4ioz.satcombo.domain.BruitSolaire
import fr.f4ioz.satcombo.domain.Qo100
import fr.f4ioz.satcombo.plateforme.Stockage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sun noise: the noise in a wide band of the receiver's spectrum measured
 * with the dish on cold sky, then on the Sun — the rise (Y factor) against
 * what the dish's size, the band and the solar index the owner typed give.
 * With a rotor through rotctld (or the simulated one), a small az/el raster
 * around the computed Sun finds where the dish really looks: started only
 * by the owner, confirmed on the page, within the rotor's limits, stopped at
 * any time. Receive only; nothing fetched from the Internet.
 */
class SoleilPc(private val station: StationPc, private val sdr: SdrPc, private val stockage: Stockage,
               private val horloge: () -> Long = System::currentTimeMillis, private val durees: Durees = Durees()) {

    /**
     * How long: a measure, a raster's cell, the pause once there; the rotor's
     * reading awaited at least [lectureMs] after a command (its loop reads
     * once a second), and taken as arrived once still for [immobileMs] (a
     * rotor that reads whole degrees never gets within tenths).
     */
    class Durees(val mesureMs: Long = 5_000L, val celluleMs: Long = 1_500L, val reposMs: Long = 600L,
                 val lectureMs: Long = 1_200L, val immobileMs: Long = 2_500L, val maxMs: Long = 60_000L)

    class Mesure(val db: Double, val t: Long, val trames: Int)
    class Cellule(val c: BruitSolaire.Case, val az: Double, val el: Double, val db: Double)

    var diametreCm: Int
        get() = stockage.getInt(CLE_DIAMETRE, 0)
        set(v) { stockage.edit().putInt(CLE_DIAMETRE, v.coerceIn(0, 2_000)).apply() }
    /** The 10.7 cm solar index as typed (SFU); 0: not given. */
    var sfu: Int
        get() = stockage.getInt(CLE_SFU, 0)
        set(v) { stockage.edit().putInt(CLE_SFU, v.coerceIn(0, 999)).apply() }
    var rendement: Int
        get() = stockage.getInt(CLE_RENDEMENT, 60)
        set(v) { stockage.edit().putInt(CLE_RENDEMENT, v.coerceIn(20, 90)).apply() }
    var tsysK: Int
        get() = stockage.getInt(CLE_TSYS, 100)
        set(v) { stockage.edit().putInt(CLE_TSYS, v.coerceIn(10, 3_000)).apply() }

    @Volatile var froid: Mesure? = null; private set
    @Volatile var soleil: Mesure? = null; private set
    /** What runs: "froid", "soleil", "balayage" or "". */
    @Volatile var enCours = ""; private set
    @Volatile var progression = 0.0; private set
    @Volatile var message = ""; private set
    @Volatile private var arret = false
    @Volatile private var cellules: List<Cellule> = emptyList()
    @Volatile private var planBalayage: List<BruitSolaire.Case> = emptyList()
    @Volatile private var demi = 1.0
    @Volatile private var pas = 0.5
    @Volatile private var elBalayage = 0.0

    /** The frequency the expected rise is computed for: the receiver's, else QO-100's downlink. */
    fun frequenceHz(): Double = sdr.etat.value.cielHz.takeIf { it > 0 }?.toDouble() ?: Qo100.BALISE_MEDIANE_HZ.toDouble()

    /** Why the raster cannot run now, or null. */
    fun empecheBalayage(): String? {
        val r = station.stationRotor.etat.value
        return when {
            !r.rotorConnected -> "aucun rotor connecté (onglet Station)"
            !(r.rotorLink == "ROTCTLD" || r.rotorSim) -> "le balayage passe par rotctld (ou le rotor simulé) : choisir la liaison rotctld"
            r.rotorAzOnly -> "rotor en azimut seul : pas de balayage en site"
            !sdr.etat.value.actif -> "le récepteur est arrêté (onglet SDR)"
            station.etat.value.observateur == null -> "la position de la station n'est pas connue"
            else -> null
        }
    }

    private fun bornes(): BruitSolaire.Bornes {
        val r = station.stationRotor.etat.value
        return BruitSolaire.Bornes(r.rotorMaxAz.toDouble(), if (r.rotorAzStop == "SOUTH") 180.0 else 0.0,
            maxOf(0, r.rotorMinEl).toDouble(), if (r.rotorFlip) 90.0 else r.rotorMaxEl.toDouble())
    }

    private fun positionSoleil(t: Long = horloge()): Pair<Double, Double>? =
        station.etat.value.observateur?.let { BruitSolaire.soleil(it.latDeg, it.lonDeg, t) }

    /**
     * The wide band noise over [dureeMs]: each new spectrum's middle 60 %
     * (some 100 kHz, the filter's edges left out), the signals in it set
     * aside, the frames' powers averaged. Null when nothing came.
     */
    private fun mesureBruit(dureeMs: Long, avance: (Double) -> Unit = {}): Mesure? {
        val l = ArrayList<Double>()
        var vue = sdr.trames
        val debut = horloge()
        while (!arret && horloge() - debut < dureeMs) {
            Thread.sleep(60)
            val sp = sdr.spectre ?: continue
            if (sdr.trames == vue) continue
            vue = sdr.trames
            BruitSolaire.bruitLargeBande(sp, sp.size / 5, sp.size - sp.size / 5)?.let { l += it }
            avance(((horloge() - debut).toDouble() / dureeMs).coerceIn(0.0, 1.0))
        }
        return BruitSolaire.moyennePuissance(l)?.let { Mesure(it, horloge(), l.size) }
    }

    /** One measure ("froid" or "soleil"), five seconds, in the background. */
    @Synchronized fun mesure(quoi: String): JSONObject {
        if (quoi != "froid" && quoi != "soleil") return refus("mesure inconnue")
        if (enCours.isNotEmpty()) return refus("une mesure est déjà en cours")
        if (!sdr.etat.value.actif) return refus("le récepteur est arrêté (onglet SDR)")
        enCours = quoi; arret = false; progression = 0.0; message = ""
        Thread {
            try {
                val m = mesureBruit(durees.mesureMs) { progression = it }
                if (m == null) message = "aucun spectre reçu : le récepteur tourne-t-il ?"
                else if (quoi == "froid") froid = m else soleil = m
            } finally { enCours = ""; progression = 0.0 }
        }.apply { isDaemon = true; name = "soleil-mesure" }.start()
        return JSONObject().put("ok", true)
    }

    /**
     * The raster around the Sun, [demiDeg] each way by [pasDeg]: only after
     * the owner confirmed ([confirme]), only the cells within the rotor's
     * limits, tracking stopped. Each cell aimed at the Sun of that moment
     * plus its offset, the rotor awaited, the noise measured.
     */
    @Synchronized fun balaye(confirme: Boolean, demiDeg: Double, pasDeg: Double): JSONObject {
        if (!confirme) return refus("à confirmer : le rotor va bouger")
        if (enCours.isNotEmpty()) return refus("une mesure est déjà en cours")
        empecheBalayage()?.let { return refus(it) }
        val (az, el) = positionSoleil() ?: return refus("la position de la station n'est pas connue")
        val b = bornes()
        val plan = BruitSolaire.planDansLesBornes(az, el, demiDeg, pasDeg, b)
        if (plan.size < 3) return refus(if (el < b.elMinDeg) "le Soleil est trop bas pour le rotor" else "le Soleil est hors de portée du rotor")
        enCours = "balayage"; arret = false; progression = 0.0; message = ""
        demi = demiDeg; pas = pasDeg; planBalayage = plan; cellules = emptyList(); elBalayage = el
        Thread {
            try { deroule(plan, b) } catch (e: Exception) { message = "balayage interrompu : " + (e.message ?: e.javaClass.simpleName) }
            finally { enCours = ""; progression = 0.0 }
        }.apply { isDaemon = true; name = "soleil-balayage" }.start()
        return JSONObject().put("ok", true).put("cellules", plan.size)
    }

    private fun deroule(plan: List<BruitSolaire.Case>, b: BruitSolaire.Bornes) {
        val rotor = station.stationRotor
        val faites = ArrayList<Cellule>()
        for ((k, c) in plan.withIndex()) {
            if (arret) { message = "balayage arrêté"; return }
            if (empecheBalayage() != null) { message = "balayage arrêté : " + empecheBalayage(); return }
            val (sa, se) = positionSoleil() ?: return
            val (a, e) = BruitSolaire.vise(sa, se, c)
            // The Sun moved since the plan: a cell now beyond the limits is skipped, never clamped.
            if (!BruitSolaire.atteignable(a, e, b)) continue
            if (!kotlinx.coroutines.runBlocking { rotor.vers(a, e) }) { message = "le rotor refuse ou ne répond pas : balayage arrêté"; return }
            attendArrivee(a, e)
            if (arret) { message = "balayage arrêté"; return }
            Thread.sleep(durees.reposMs)
            val m = mesureBruit(durees.celluleMs) ?: continue
            faites += Cellule(c, a, e, m.db)
            cellules = faites.toList()
            progression = (k + 1).toDouble() / plan.size
        }
        message = if (faites.size >= 3) "balayage terminé" else "trop peu de mesures"
    }

    /**
     * Until the rotor reads within 0.6° of [az]/[el], or stays still (a
     * reading newer than the command, never the one before it); at most
     * [Durees.maxMs]. Without any reading, a fixed wait.
     */
    private fun attendArrivee(az: Double, el: Double) {
        val debut = horloge()
        var avant: Pair<Double, Double>? = null; var depuis = debut
        while (!arret && horloge() - debut < durees.maxMs) {
            Thread.sleep(100)
            if (horloge() - debut < durees.lectureMs) continue
            val r = station.stationRotor.etat.value
            val la = r.rotorActualAz; val le = r.rotorActualEl
            if (la == null || le == null) { if (horloge() - debut >= durees.lectureMs + durees.immobileMs) return else continue }
            if (Math.abs(BruitSolaire.ecartAz(la, az)) <= 0.6 && Math.abs(le - el) <= 0.6) return
            val a = avant
            if (a == null || Math.abs(BruitSolaire.ecartAz(a.first, la)) > 0.05 || Math.abs(a.second - le) > 0.05) { avant = la to le; depuis = horloge() }
            else if (horloge() - depuis >= durees.immobileMs) return
        }
    }

    /** Stops what runs; a raster also stops the rotor. */
    fun arrete(): JSONObject {
        val balayage = enCours == "balayage"
        arret = true
        if (balayage) station.stationRotor.rotorStopNow()
        return JSONObject().put("ok", true)
    }

    fun etat(): JSONObject {
        val f = frequenceHz()
        val o = JSONObject().put("ok", true).put("diametre", diametreCm).put("sfu", sfu).put("rendement", rendement).put("tsys", tsysK)
            .put("frequence", f).put("enCours", enCours).put("progression", progression).put("message", message)
            .put("recepteur", sdr.etat.value.actif)
            // A tuner's automatic gain lowers the rise it measures.
            .put("gainAuto", station.settings.sdrGainTenthDb < 0)
        val att = if (diametreCm > 0 && sfu > 0) BruitSolaire.attendu(diametreCm / 100.0, f, sfu.toDouble(), rendement / 100.0, tsysK.toDouble()) else null
        att?.let { a -> o.put("attendu", JSONObject().put("flux", r1(a.fluxSfu)).put("lobe", r2(a.lobeDeg)).put("deltaT", Math.round(a.deltaTK)).put("y", r1(a.yDb))) }
        froid?.let { o.put("froid", JSONObject().put("db", r2(it.db)).put("t", it.t).put("n", it.trames)) }
        soleil?.let { o.put("soleil", JSONObject().put("db", r2(it.db)).put("t", it.t).put("n", it.trames)) }
        val fr = froid; val so = soleil
        if (fr != null && so != null) {
            val y = BruitSolaire.facteurY(so.db, fr.db)
            o.put("y", r1(y))
            att?.let { a -> BruitSolaire.tsysDepuisY(y, a.deltaTK)?.let { o.put("tsysMesure", Math.round(it)) }; o.put("conforme", BruitSolaire.conforme(y, a.yDb)) }
        }
        positionSoleil()?.let { (az, el) -> o.put("position", JSONObject().put("az", r2(az)).put("el", r2(el))) }
        val r = station.stationRotor.etat.value
        o.put("rotor", JSONObject().put("pret", empecheBalayage() == null).put("pourquoi", empecheBalayage() ?: "")
            .put("az", r.rotorActualAz ?: JSONObject.NULL).put("el", r.rotorActualEl ?: JSONObject.NULL)
            .put("simule", r.rotorSim).put("bornes", bornes().let { b -> JSONObject().put("azMax", b.azMaxDeg).put("elMin", b.elMinDeg).put("elMax", b.elMaxDeg) }))
        val cs = cellules
        if (cs.isNotEmpty() || enCours == "balayage") {
            val bal = JSONObject().put("demi", demi).put("pas", pas).put("prevues", planBalayage.size)
                .put("cellules", JSONArray(cs.map { c -> JSONArray().put(c.c.dXDeg).put(c.c.dElDeg).put(r2(c.db)) }))
            BruitSolaire.pic(cs.map { it.c to it.db })?.let { p ->
                bal.put("pic", JSONObject().put("dX", r2(p.dXDeg)).put("dEl", r2(p.dElDeg)).put("dAz", r2(BruitSolaire.enAzimut(p.dXDeg, elBalayage + p.dElDeg)))
                    .put("db", r2(p.db)).put("montee", r1(p.monteeDb)))
            }
            o.put("balayage", bal)
        }
        return o
    }

    fun sert(chemin: String, methode: String, q: (String) -> String): ReponseWeb {
        val o = if (methode != "POST") etat() else when (chemin) {
            "/api/soleil/reglages" -> {
                q("diametre").toIntOrNull()?.let { diametreCm = it }
                q("sfu").toIntOrNull()?.let { sfu = it }
                q("rendement").toIntOrNull()?.let { rendement = it }
                q("tsys").toIntOrNull()?.let { tsysK = it }
                etat()
            }
            "/api/soleil/mesure" -> mesure(q("quoi"))
            "/api/soleil/oublie" -> { froid = null; soleil = null; cellules = emptyList(); planBalayage = emptyList(); message = ""; JSONObject().put("ok", true) }
            "/api/soleil/balayer" -> balaye(q("confirme") == "1", q("demi").toDoubleOrNull() ?: 1.5, q("pas").toDoubleOrNull() ?: 0.5)
            "/api/soleil/arreter" -> arrete()
            else -> refus("inconnu")
        }
        return ReponseWeb("200 OK", "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))
    }

    private fun refus(t: String) = JSONObject().put("ok", false).put("erreur", t)

    companion object {
        const val CLE_DIAMETRE = "pc_soleil_diametre_cm"
        const val CLE_SFU = "pc_soleil_sfu"
        const val CLE_RENDEMENT = "pc_soleil_rendement"
        const val CLE_TSYS = "pc_soleil_tsys"
        private fun r1(v: Double) = Math.round(v * 10) / 10.0
        private fun r2(v: Double) = Math.round(v * 100) / 100.0
    }
}
