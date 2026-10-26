/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.demo.SonDistant
import fr.f4ioz.satcombo.domain.ClasseQo100
import fr.f4ioz.satcombo.domain.MonteeQo100
import fr.f4ioz.satcombo.domain.MonteeQo100.PlanPluto
import fr.f4ioz.satcombo.domain.Qo100
import fr.f4ioz.satcombo.domain.SignalQo100
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import fr.f4ioz.satcombo.sdr.EmetteurDatv
import fr.f4ioz.satcombo.sdr.EtatEmission
import fr.f4ioz.satcombo.sdr.PlutoEmission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Pluto's narrow band from the page's side to the samples, offline: a
 * fake transmitter keeps what it is asked and pulls the samples by hand.
 * The guards: nothing at start, the watchdog, the time-out, LEILA, the dummy
 * load, the network, the band plan, the caps. No radio is ever touched.
 */
class EmissionBandeEtroitePcTest {

    /** A transmitter that only remembers. */
    private class FauxTx(val r: PlutoEmission.Reglage, val max: Double, val producteur: (ShortArray) -> Unit) : EmetteurDatv {
        @Volatile var emet = true
        @Volatile var raison = ""
        @Volatile var gain = minOf(r.gainDb, max, r.plafondDb, 0.0)
        override val nom = "faux Pluto"
        override fun etatCommun() = EtatEmission(emet, 0, 0, r.frequenceHz, gain, 0, 0, raison, "")
        override fun arrete(raison: String) { emet = false; this.raison = raison }
        override fun regleGain(db: Double): Double? { gain = minOf(db, max, r.plafondDb, 0.0); return gain }
        /** [paires] pairs as the Pluto would take them. */
        fun tire(paires: Int): ShortArray = ShortArray(2 * paires).also { producteur(it) }
    }

    private class Banc(
        var t: Long = 1_000_000L,
        var indicatif: String = "F4IOZ",
        var descente: Long = 10_489_800_000L,
        var leila: Boolean = false,
        var autre: String? = null,
        var reseau: Boolean = false,
        var signaux: List<SignalQo100>? = null,
        val stockage: StockageMemoire = StockageMemoire(),
    ) {
        val txs = ArrayList<FauxTx>()
        val tx get() = txs.last()
        val b = EmissionBandeEtroitePc(stockage, { indicatif }, autreEmission = { autre }, gainMaxDb = { -10.0 }, reseauAutorise = { reseau },
            descenteCockpit = { descente }, signauxQo100 = { signaux }, leila = { leila }, horloge = { t },
            fabrique = { r, max, p -> FauxTx(r, max, p).also { txs += it } to null }, sonde = { false })
    }

    @Test fun `rien n emet tout seul`() {
        val k = Banc()
        repeat(20) { k.t += 200; k.b.tic() }
        assertTrue(k.txs.isEmpty())
        assertFalse(k.b.emet)
        // Every start comes back into the dummy load, whatever was set before.
        k.b.charge = false
        assertTrue(Banc(stockage = k.stockage).b.charge)
        assertTrue(k.b.json("x").getBoolean("pret"))
    }

    @Test fun `sur charge le gain est bride et la frequence juste`() {
        val k = Banc()
        k.b.gainDb = -5.0
        assertNull(k.b.appuie("page", false, "", confirme = false))
        assertTrue(k.b.emet)
        val r = k.tx.r
        // The LO parked below the transponder, the gain held at −40 dB by the dummy load.
        assertEquals(PlanPluto.pour(2_400_300_000L, 0).loHz, r.frequenceHz)
        assertEquals(-40.0, r.plafondDb, 0.0)
        assertEquals(-40.0, k.tx.gain, 0.0)
        assertEquals(PlanPluto.DEBIT, r.debit)
        // The time-out handed down to the transmitter too.
        assertTrue(r.dureeMs in 180_000L..190_000L)
        k.b.relache("fin")
        assertFalse(k.b.emet)
        assertFalse(k.tx.emet)
    }

    @Test fun `le micro sort en BLU haute a la bonne frequence`() {
        val k = Banc()
        assertNull(k.b.appuie("page", false, "", false))
        // 300 ms of a 1 kHz tone from the page's microphone, in 20 ms frames.
        var ph = 0.0
        repeat(15) {
            val pcm = ShortArray(960) { ph += 2 * PI * 1_000 / 48_000; (sin(ph) * 0.8 * 32767).roundToInt().toShort() }
            k.b.micro("page", SonDistant.Lue(0, 48_000, it.toLong(), 0.0, pcm))
        }
        val p = PlanPluto.pour(2_400_300_000L, 0)
        val n = PlanPluto.DEBIT.toInt() / 4
        val b = k.tx.tire(n)
        // Taken back to the dial: the tone above it (USB), not below.
        fun raie(f: Double): Double {
            var a = 0.0; var c = 0.0
            val debut = n / 4
            for (i in debut until n) {
                val w = -2 * PI * (p.ncoHz + f) * i / PlanPluto.DEBIT
                val re = b[2 * i].toDouble(); val im = b[2 * i + 1].toDouble()
                a += re * cos(w) - im * sin(w); c += re * sin(w) + im * cos(w)
            }
            return hypot(a, c) / (n - debut)
        }
        val haut = raie(1_000.0)
        assertTrue(haut > 3_000)
        assertTrue(20 * log10(raie(-1_000.0) / haut) < -35)
        assertTrue(20 * log10(raie(0.0) / haut) < -35)
        // Another client's microphone never reaches it.
        val avant = k.b.json("page").getLong("ignorees")
        k.b.micro("autre", SonDistant.Lue(0, 48_000, 0, 0.0, ShortArray(960)))
        assertEquals(avant + 1, k.b.json("page").getLong("ignorees"))
    }

    @Test fun `le chien de garde et la minuterie`() {
        val k = Banc()
        assertNull(k.b.appuie("page", false, "", false))
        // The page says it is there: kept.
        repeat(10) { k.t += 400; assertTrue(k.b.vie("page")); k.b.tic() }
        assertTrue(k.b.emet)
        // Silent 2 s: released, the transmitter off.
        k.t += 2_500; k.b.tic()
        assertFalse(k.b.emet)
        assertFalse(k.tx.emet)
        // The time-out, the page alive all along.
        k.b.totMin = 1
        assertNull(k.b.appuie("page", false, "", false))
        repeat(160) { k.t += 400; k.b.vie("page"); k.b.tic() }
        assertFalse(k.b.emet)
        assertTrue(k.b.json("page").getString("raison").contains("minuterie"))
    }

    @Test fun `LEILA coupe tout de suite`() {
        val k = Banc()
        k.b.charge = false
        assertNull(k.b.appuie("page", false, "", confirme = true))
        k.leila = true
        k.t += 200; k.b.tic()
        assertFalse(k.b.emet)
        assertFalse(k.tx.emet)
        assertTrue(k.b.json("page").getString("raison").contains("LEILA"))
        // Not again at once, even LEILA gone.
        k.leila = false
        assertTrue(k.b.appuie("page", false, "", true)!!.contains("LEILA"))
        k.t += EmissionBandeEtroitePc.LEILA_ATTENTE_MS + 1
        assertNull(k.b.appuie("page", false, "", true))
    }

    @Test fun `sur l air seulement confirme et sous le plafond`() {
        val k = Banc()
        k.b.charge = false
        assertNotNull(k.b.appuie("page", false, "", confirme = false))
        assertTrue(k.txs.isEmpty())
        k.b.gainDb = -2.0
        k.b.plafondDb = -20.0
        assertNull(k.b.appuie("page", false, "", confirme = true))
        assertEquals(0.0, k.tx.r.plafondDb, 0.0)
        // The lowest of the caps: this transmitter's own (−20), under the shared one (−10).
        assertEquals(-20.0, k.tx.max, 0.0)
        assertEquals(-20.0, k.tx.gain, 0.0)
        // Changed while transmitting: clamped likewise.
        k.b.reglage(mapOf("gain" to "0"), local = true)
        assertEquals(-20.0, k.tx.gain, 0.0)
    }

    @Test fun `les refus`() {
        val k = Banc()
        // From another machine: only with « émission par le réseau autorisée ».
        assertTrue(k.b.appuie("loin", true, "192.168.1.9", false)!!.contains("réseau"))
        k.reseau = true
        assertNull(k.b.appuie("loin", true, "192.168.1.9", false))
        // One client holds it; the others may only stop it.
        assertNotNull(k.b.appuie("page", false, "", false))
        k.b.relache("STOP depuis la page")
        assertFalse(k.b.emet)
        // The cockpit on the middle beacon: never.
        k.descente = Qo100.BALISE_MEDIANE_HZ
        assertTrue(k.b.appuie("page", false, "", false)!!.contains("balise"))
        k.descente = 10_489_800_000L
        // No callsign, another transmission.
        k.indicatif = ""
        assertTrue(k.b.appuie("page", false, "", false)!!.contains("indicatif"))
        k.indicatif = "F4IOZ"; k.autre = "une émission DATV est en cours"
        assertTrue(k.b.appuie("page", false, "", false)!!.contains("DATV"))
        k.autre = null
        // The guards change from this PC only.
        assertFalse(k.b.reglage(mapOf("plafond" to "0"), local = false).getBoolean("ok"))
        assertFalse(k.b.reglage(mapOf("charge" to "0"), local = false).getBoolean("ok"))
        assertTrue(k.txs.size == 1)
    }

    @Test fun `la frequence suit le cockpit pendant l emission sauf vers une balise`() {
        val k = Banc()
        assertNull(k.b.appuie("page", false, "", false))
        k.descente = 10_489_810_000L
        assertNull(k.tx.r.garde())
        k.descente = Qo100.BALISE_MEDIANE_HZ
        assertNotNull(k.tx.r.garde())
    }

    @Test fun `la CW et la mesure de la correction du Pluto`() {
        val k = Banc()
        k.b.reglage(mapOf("mode" to "cw", "lie" to "0", "cadran" to "2400025000"), local = true)
        assertEquals(MonteeQo100.Mode.CW, k.b.mode)
        // No measurement before transmitting on the air.
        assertFalse(k.b.mesureCorrection().getBoolean("ok"))
        k.b.charge = false
        assertNull(k.b.appuie("page", false, "", true))
        // The key, by the holder only.
        assertFalse(k.b.cw("autre", mapOf("touche" to "1")).getBoolean("ok"))
        assertTrue(k.b.cw("page", mapOf("touche" to "1")).getBoolean("ok"))
        // Our carrier heard 2.4 kHz high on the downlink: about 1 ppm.
        val attendu = Qo100.descenteDepuisMontee(2_400_025_700L)
        k.signaux = listOf(SignalQo100(1, attendu + 2_400, 50, ClasseQo100.CW, 0.9, 0, 100, 20.0, ""),
            SignalQo100(2, attendu + 15_000, 2500, ClasseQo100.BLU, 0.9, 0, 100, 5.0, ""))
        val m = k.b.mesureCorrection()
        assertTrue(m.toString(), m.getBoolean("ok"))
        assertEquals(2_400.0, k.b.correctionHz.toDouble(), 2.0)
        // Applied at the next press: the LO asked lower.
        k.b.relache("fin")
        assertNull(k.b.appuie("page", false, "", true))
        assertTrue(k.tx.r.frequenceHz < PlanPluto.LO_HZ)
        // A typed correction beyond ±50 ppm refused.
        k.b.relache("fin")
        assertFalse(k.b.reglage(mapOf("correction" to "500000"), local = true).getBoolean("ok"))
    }

    @Test fun `le niveau par rapport a la balise`() {
        val k = Banc()
        k.b.charge = false
        assertNull(k.b.appuie("page", false, "", true))
        k.signaux = listOf(SignalQo100(1, Qo100.BALISE_MEDIANE_HZ, 2400, ClasseQo100.BALISE, 1.0, 0, 100, 15.0, ""),
            SignalQo100(2, 10_489_801_500L, 2400, ClasseQo100.BLU, 0.9, 0, 100, 18.0, ""))
        k.t += 200; k.b.vie("page"); k.b.tic()
        val n = k.b.json("page").getJSONObject("niveau")
        assertEquals(3.0, n.getDouble("ecart"), 0.01)
        assertTrue(n.getBoolean("tropFort"))
    }

    @Test fun `un Pluto absent est dit sans rien emettre`() {
        val port = ServerSocket(0).use { it.localPort }
        val b = EmissionBandeEtroitePc(StockageMemoire(), { "F4IOZ" }, plutoHote = { "127.0.0.1" }, plutoPort = { port },
            descenteCockpit = { 10_489_800_000L }, sonde = { false })
        val r = b.appuie("page", false, "", false)
        assertTrue(r.toString(), r!!.contains("injoignable"))
        assertFalse(b.emet)
    }

    @Test fun `les profils fixe et portable`() {
        val k = Banc()
        val j = k.b.json("x")
        assertEquals("Fixe", j.getString("profil"))
        assertEquals("qo100-pluto-helice", j.getJSONObject("estimation").getString("montage"))
        assertTrue(k.b.reglage(mapOf("profil" to "Portable"), local = true).getBoolean("ok"))
        assertEquals("qo100-portable-35", k.b.json("x").getJSONObject("estimation").getString("montage"))
        val p = """{"nom":"Fixe","chaine":"pluto","antenne":"helice20","ampliW":20,"ampliGain":40,"pertes":2}"""
        assertTrue(k.b.reglage(mapOf("profilJson" to p), local = true).getBoolean("ok"))
        assertEquals(20.0, k.b.profil.ampliW, 0.0)
        assertFalse(k.b.json("x").getJSONObject("estimation").isNull("gainPluto"))
    }
}
