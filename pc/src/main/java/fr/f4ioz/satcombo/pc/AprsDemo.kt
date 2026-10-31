/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.aprs.Adresse
import fr.f4ioz.satcombo.aprs.Ax25
import fr.f4ioz.satcombo.aprs.Trame
import fr.f4ioz.satcombo.data.SatPass
import java.io.File
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The demonstration's APRS: two days of made-up frames around the
 * demonstration station (Clermont-Ferrand), written where SatMe-PC keeps
 * what it hears (`aprs/AAAA-MM-JJ.txt`): houses, a hilltop digipeater, an
 * iGate, a weather station, a car driving with its course and speed, a
 * bicycle in compressed form, a balloon, an object, a message, telemetry,
 * a station from the internet through the iGate, and stations far away
 * relayed by the ISS during its real passes of the last days.
 *
 * **Nothing is anybody's**: every callsign is in the Q series (never
 * given to a country), as in [GenerateurDemo]. Nothing is sent.
 */
class AprsDemo(private val maintenant: Long, private val lat0: Double, private val lon0: Double, graine: Long = GenerateurDemo.GRAINE) {

    private val rnd = java.util.Random(graine xor 0x41505253L)
    private val lignes = ArrayList<Pair<Long, String>>()

    private fun ajoute(t: Long, src: String, chemin: List<String>, info: String, sat: String = "") {
        if (t > maintenant - 60_000L || t < maintenant - 47 * 3_600_000L) return
        val tr = Trame(Adresse.de("APSATM"), Adresse.de(src), chemin.map { Adresse.de(it) }, info.toByteArray(Charsets.ISO_8859_1))
        lignes += t to "$t\t$sat\t" + Base64.getEncoder().encodeToString(Ax25.encode(tr))
    }

    private fun la(v: Double): String {
        val a = Math.abs(v); val d = floor(a).toInt(); val m = (a - d) * 60
        return "%02d%05.2f%s".format(Locale.US, d, m, if (v >= 0) "N" else "S")
    }
    private fun lo(v: Double): String {
        val a = Math.abs(v); val d = floor(a).toInt(); val m = (a - d) * 60
        return "%03d%05.2f%s".format(Locale.US, d, m, if (v >= 0) "E" else "W")
    }
    /** "=4546.10N/00310.20E>" : a plain position with its symbol. */
    private fun pos(lat: Double, lon: Double, sym: String) = "=" + la(lat) + sym[0] + lo(lon) + sym[1]

    private fun b91(v: Long, n: Int): String {
        val c = CharArray(n); var x = v
        for (i in n - 1 downTo 0) { c[i] = (33 + (x % 91)).toInt().toChar(); x /= 91 }
        return String(c)
    }
    /** A compressed position (APRS 1.01 chapter 9) with course and speed. */
    private fun compressee(lat: Double, lon: Double, sym: String, cap: Int, noeuds: Double): String {
        val y = ((90 - lat) * 380926).roundToInt().toLong(); val x = ((180 + lon) * 190463).roundToInt().toLong()
        val c = (33 + (cap / 4).coerceIn(0, 89)).toChar()
        val s = (33 + (Math.log(noeuds + 1) / Math.log(1.08)).roundToInt().coerceIn(0, 89)).toChar()
        return "!" + sym[0] + b91(y, 4) + b91(x, 4) + sym[1] + c + s + "G"
    }

    private fun km(dLat: Double, dLon: Double) = Pair(lat0 + dLat / 111.0, lon0 + dLon / (111.0 * cos(Math.toRadians(lat0))))

    /** Every [pasMin] minutes (± a little) over the last [heures] hours. */
    private fun chaque(pasMin: Int, heures: Int = 47, f: (Long) -> Unit) {
        var t = maintenant - heures * 3_600_000L + rnd.nextInt(pasMin * 60_000)
        while (t < maintenant - 60_000L) { f(t); t += pasMin * 60_000L + rnd.nextInt(120_000) - 60_000 }
    }

    /** The frames, [passagesIss] the ISS's passes of the last days. Returns how many were written. */
    fun genere(dossier: File, passagesIss: List<SatPass>): Int {
        val digi = "QF6DIG-2"
        val (dLa, dLo) = km(-1.0, -9.5)   // the hilltop west of the town
        chaque(30) { t -> ajoute(t, digi, emptyList(), pos(dLa, dLo, "/#") + "PHG5730 Digipeater du sommet (fictif)") }
        chaque(120) { t -> ajoute(t, digi, emptyList(), "T#%03d,%03d,%03d,000,%03d,200,00000000".format(Locale.US, (t / 7_200_000L % 1000).toInt(), 130 + rnd.nextInt(10), 12 + rnd.nextInt(4), 40 + rnd.nextInt(30))) }

        val (iLa, iLo) = km(2.0, 3.0)
        chaque(20) { t -> ajoute(t, "QF5IGT-10", emptyList(), "=" + la(iLa) + "I" + lo(iLo) + "&iGate de réception, rien en émission (fictif)") }
        // A station heard through the iGate from the internet (a third-party frame).
        val (nLa, nLo) = km(60.0, 90.0)
        chaque(45) { t -> ajoute(t, "QF5IGT-10", emptyList(), "}QF3NET-5>APRS,TCPIP,qAC,QF9GW:" + pos(nLa, nLo, "/-") + "Station vue par Internet (fictive)") }

        val (mLa, mLo) = km(4.0, 6.0)
        chaque(15) { t ->
            val h = ((t / 3_600_000L) % 24).toInt()
            val temp = 8 + 6 * Math.sin((h - 9) / 24.0 * 2 * Math.PI)
            val f = (temp * 9 / 5 + 32).roundToInt()
            ajoute(t, "QF4WX-13", emptyList(), "=" + la(mLa) + "/" + lo(mLo) + "_%03d/%03dg%03dt%03dr000h%02db%05d Station météo du club (fictive)".format(Locale.US,
                200 + rnd.nextInt(60), 3 + rnd.nextInt(6), 8 + rnd.nextInt(8), f, 70 + rnd.nextInt(25), 10125 + rnd.nextInt(20)))
        }

        val maisons = listOf(Triple("QF1ABC", km(3.0, -2.0), "QTH, antenne verticale (fictif)"), Triple("QF5ZZA", km(-12.0, 15.0), "Écoute 144,800 MHz (fictif)"),
            Triple("QF2HOT", km(35.0, -28.0), "Radio-club (fictif)"))
        maisons.forEachIndexed { i, (c, p, com) ->
            chaque(30 + 10 * i) { t -> ajoute(t, c, if (i == 2) listOf("$digi*", "WIDE2-1") else emptyList(), pos(p.first, p.second, "/-") + com) }
        }
        chaque(240) { t -> ajoute(t, "QF1ABC", emptyList(), ">QRV sur 145,500 MHz ce soir") }
        // An object put out by QF1ABC, and a message from it to the car.
        chaque(60, 30) { t -> val (a, b) = km(1.5, 1.0); ajoute(t, "QF1ABC", emptyList(), ";SALON-RA *111111z" + la(a) + "\\" + lo(b) + "iSalon radioamateur, samedi 9 h (fictif)") }
        ajoute(maintenant - 50 * 60_000L, "QF1ABC", emptyList(), ":QF8MOB-9 :Bonne route, à ce soir !{12")

        // The car: a trip of two hours ending 25 minutes ago, through the digipeater, its course and speed given.
        val fin = maintenant - 25 * 60_000L
        var t = fin - 120 * 60_000L
        var (cLa, cLo) = km(-40.0, -55.0)
        var cap = 45
        while (t <= fin) {
            cap = (cap + rnd.nextInt(21) - 10 + 360) % 360
            val v = 70 + rnd.nextInt(40)
            val pas = v / 60.0 * 3   // km in 3 min
            cLa += pas * cos(Math.toRadians(cap.toDouble())) / 111.0
            cLo += pas * Math.sin(Math.toRadians(cap.toDouble())) / (111.0 * cos(Math.toRadians(cLa)))
            ajoute(t, "QF8MOB-9", listOf("$digi*", "WIDE2-1"), pos(cLa, cLo, "/>") + "%03d/%03d/A=%06d En route (fictif)".format(Locale.US, cap, (v / 1.852).roundToInt(), 1200 + rnd.nextInt(800)))
            t += 3 * 60_000L
        }
        ajoute(fin - 60 * 60_000L, "QF8MOB-9", listOf("$digi*"), ">En mobile, QRV 145,500")

        // A bicycle in compressed form, heard direct, the last 90 minutes.
        var (bLa, bLo) = km(1.0, 4.0)
        t = maintenant - 90 * 60_000L
        while (t < maintenant - 2 * 60_000L) {
            bLa += 0.0015; bLo += 0.001 * (rnd.nextDouble() - 0.3)
            ajoute(t, "QF2CMP-7", emptyList(), compressee(bLa, bLo, "/b", 30 + rnd.nextInt(30), 8.0 + rnd.nextInt(4)) + "Vélo (fictif)")
            t += 5 * 60_000L
        }

        // A balloon going up, far, through the digipeater.
        var (oLa, oLo) = km(-90.0, 110.0)
        t = maintenant - 5 * 3_600_000L
        var alt = 300
        while (t < maintenant - 3 * 3_600_000L) {
            oLa += 0.01; oLo += 0.03; alt += 450
            ajoute(t, "QF7BAL-11", listOf("$digi*"), pos(oLa, oLo, "/O") + "/A=%06d Ballon de démonstration (fictif)".format(Locale.US, (alt / 0.3048).roundToInt()))
            t += 4 * 60_000L
        }

        // Through the ISS during its passes: stations far away, the ISS's own beacon, a few each pass.
        val loin = listOf(
            Triple("QE4ABC", 40.42 to -3.70, "/-"), Triple("QI1XYZ", 41.90 to 12.50, "/-"), Triple("QG7ISS", 51.5 to -0.12, "/>"),
            Triple("QD1SAT", 52.52 to 13.40, "/-"), Triple("QC3PRT", 38.72 to -9.14, "/["), Triple("QO9ALP", 46.95 to 7.45, "/-"),
            Triple("QS5PNT", 45.07 to 7.69, "/;"), Triple("QF6SAT", 43.30 to 5.37, "/-"))
        passagesIss.filter { it.losEpochMs in (maintenant - 47 * 3_600_000L)..(maintenant - 60_000L) && it.maxElevationDeg >= 10 }.forEach { p ->
            val n = 3 + rnd.nextInt(4)
            val duree = (p.losEpochMs - p.aosEpochMs).coerceAtLeast(60_000L)
            ajoute(p.aosEpochMs + duree / 3, "RS0ISS", emptyList(), ">ARISS-International Space Station", sat = "ISS")
            loin.shuffled(rnd).take(n).forEachIndexed { i, (c, ll, sym) ->
                val q = p.aosEpochMs + duree * (i + 1) / (n + 2)
                ajoute(q, c, listOf("RS0ISS*", "WIDE2-1"), pos(ll.first, ll.second, sym) + "Via l'ISS (fictif)", sat = "ISS")
            }
        }

        val jour = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val d = File(dossier, "aprs")
        d.deleteRecursively(); d.mkdirs()
        File(dossier, "aprs-trophees.txt").delete()
        lignes.sortedBy { it.first }.groupBy { jour.format(Date(it.first)) }.forEach { (j, l) ->
            File(d, "$j.txt").writeText(l.joinToString("") { it.second + "\n" })
        }
        return lignes.size
    }
}
