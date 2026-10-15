/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.phonie.ProtocolePhonie
import fr.f4ioz.satcombo.phonie.ProtocolePhonie.Message
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/** « Phonie → texte » without Python nor a GPU: a fake engine, in the process or as a child process. */
class PhoniePcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-phonie-" + System.nanoTime()).apply { mkdirs() }
    private val ouverts = ArrayList<PhoniePc>()
    @After fun fin() { ouverts.forEach { it.arrete() }; dossier.deleteRecursively() }

    /** An engine answering [texte] for every over, after [delaiMs]; [panne]: fails the first [panne] overs as a crash. */
    private class Faux(val texte: String, val delaiMs: Long = 0, var panne: Int = 0) : MoteurPhonie {
        var demarrages = 0; var recus = 0
        override fun demarre(): Message.Pret { demarrages++; return Message.Pret("turbo", "cpu", "int8", 0.1, "") }
        override fun transcrit(id: Long, pcm: FloatArray, langue: String, traduire: Boolean): Message {
            recus++
            if (panne > 0) { panne--; throw IllegalStateException("le moteur s'est arrêté (code 134)") }
            Thread.sleep(delaiMs)
            return Message.Reponse(id, "fr", 0.99, 0.2, listOf(ProtocolePhonie.Morceau(0.0, 3.0, texte, -0.2, 0.01, 1.2)),
                if (traduire) "translated" else "")
        }
        override fun arrete() {}
    }

    private fun phonie(faux: Faux): PhoniePc {
        val st = StockageMemoire()
        return PhoniePc(st, InstallPhoniePc(dossier), File(dossier, "phonie"), { horloge }, fabrique = { faux }).also { ouverts += it }
    }

    /** The clock moves with the sound given, as it would in real time. */
    @Volatile private var horloge = 1_760_000_000_000L
    private val rnd = java.util.Random(3)
    /** [n] overs of 2 s at 48 kHz with 2 s of noise between, as a sound card's blocks. */
    private fun parle(p: PhoniePc, n: Int, sdr: Boolean = false, aussiCarte: Boolean = false) {
        val rate = 48_000
        val une = (0 until rate * 4).map { i ->
            val t = i.toDouble() / rate
            val v = if (t < 2.0) 0.0 else (0.5 + 0.5 * sin(2 * PI * 4 * t)).let { s -> s * s * (0.2 * sin(2 * PI * 300 * t) + 0.1 * sin(2 * PI * 1200 * t)) }
            ((v + rnd.nextGaussian() * 0.005) * 32767).toInt().toShort()
        }.toShortArray()
        val tout = ShortArray(une.size * n + rate * 2) { if (it < une.size * n) une[it % une.size] else (rnd.nextGaussian() * 0.005 * 32767).toInt().toShort() }
        var i = 0
        while (i < tout.size) {
            val k = minOf(4800, tout.size - i)
            val bloc = tout.copyOfRange(i, i + k)
            horloge += k * 1000L / rate
            if (sdr) p.ecouteSdr(bloc, k, rate, null) else p.ecouteCarte(bloc, k, rate)
            if (aussiCarte) p.ecouteCarte(bloc, k, rate)
            i += k
        }
    }

    private fun attend(ms: Long, condition: () -> Boolean): Boolean {
        val fin = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fin) { if (condition()) return true; Thread.sleep(20) }
        return condition()
    }

    @Test fun `coupe par defaut rien ne demarre`() {
        val f = Faux("F4IOZ")
        val p = phonie(f)
        assertFalse(p.actif)
        p.demarre()
        parle(p, 1)
        Thread.sleep(200)
        assertEquals(0, f.demarrages)
        assertEquals("coupé", p.etat)
        assertEquals(0, p.enFile)
        assertTrue(p.bulles().isEmpty())
        // Turning it on without an engine installed is refused, with a French reason.
        val r = p.action("/api/phonie", mapOf("on" to "1"))
        assertFalse(r.getBoolean("ok")); assertTrue(r.getString("erreur").contains("Installer le moteur"))
        assertFalse(p.actif)
    }

    @Test fun `une phrase devient une bulle avec ses indicatifs`() {
        val f = Faux("CQ de Fox quatre Italie Oscar Zoulou, carré Juliette Novembre un huit")
        val p = phonie(f)
        p.contexte = { src -> 10_489_750_000L to "QO-100 ($src)" }
        p.connus = { setOf("F4IOZ") }
        p.actif = true
        parle(p, 2)
        assertTrue(attend(5000) { p.bulles().size == 2 })
        val b = p.bulles().first()
        assertEquals("fr", b.langue); assertEquals(10_489_750_000L, b.freqHz); assertEquals("QO-100 (carte)", b.satellite)
        val ind = b.elements.first { it.genre.name == "INDICATIF" }
        assertEquals("F4IOZ", ind.valeur); assertTrue(ind.connu)
        assertTrue(b.elements.any { it.valeur == "JN18" })
        // Kept in the day's file, and as text.
        val j = p.jours().single()
        assertEquals(2, File(dossier, "phonie/$j.jsonl").readLines().size)
        assertTrue(p.texteJour(j)!!.contains("→ F4IOZ JN18"))
        val json = p.json(0)
        assertEquals("prêt", json.getString("etat"))
        assertEquals("FR", json.getJSONArray("bulles").getJSONObject(0).getJSONArray("elements").getJSONObject(0).getString("drapeau"))
        // Asked since the first: only the second.
        assertEquals(1, p.json(b.id).getJSONArray("bulles").length())
        // Cleared: the window only.
        p.efface(); assertTrue(p.bulles().isEmpty()); assertEquals(2, File(dossier, "phonie/$j.jsonl").readLines().size)
    }

    @Test fun `les phrases inventees ne font pas de bulle`() {
        val f = Faux("Sous-titres réalisés par la communauté d'Amara.org")
        val p = phonie(f)
        p.actif = true
        parle(p, 1)
        assertTrue(attend(3000) { f.recus == 1 })
        Thread.sleep(100)
        assertTrue(p.bulles().isEmpty())
    }

    @Test fun `le moteur en retard perd les plus anciens`() {
        val f = Faux("bonjour", delaiMs = 400)
        val p = phonie(f)
        p.actif = true
        parle(p, PhoniePc.MAX_FILE + 6)
        assertTrue(p.perdus > 0)
        assertTrue(p.retardS() > 0)
    }

    @Test fun `apres une panne il redemarre`() {
        val f = Faux("bonjour", panne = 1)
        val p = phonie(f)
        p.actif = true
        parle(p, 1)
        assertTrue(attend(2000) { p.etat == "erreur" })
        assertTrue(p.erreur.contains("nouvel essai"))
        parle(p, 1)
        assertTrue(attend(6000) { p.bulles().isNotEmpty() })
        assertEquals("prêt", p.etat)
        assertEquals(2, f.demarrages)
        p.actif = false
        assertEquals("coupé", p.etat)
    }

    @Test fun `la cle passe avant la carte en automatique`() {
        val f = Faux("bonjour")
        val p = phonie(f)
        p.actif = true
        // The card meanwhile (the dongle as its source, the same sound): not taken twice.
        parle(p, 1, sdr = true, aussiCarte = true)
        assertTrue(attend(3000) { p.bulles().isNotEmpty() })
        Thread.sleep(200)
        assertEquals(1, p.bulles().size)
        assertEquals("sdr", p.bulles().single().source)
    }

    // ------------------------------------------------------------------ the child process

    private fun java() = File(System.getProperty("java.home"), "bin/java").absolutePath

    private fun faux(vararg args: String) = MoteurProcessus(listOf(java(), "-cp", System.getProperty("java.class.path"), FauxMoteurPhonie::class.java.name) + args,
        delaiChargementMs = 20_000)

    @Test fun `le processus parle le protocole`() {
        val m = faux()
        val pret = m.demarre()
        assertEquals("faux", pret.modele)
        val r = m.transcrit(5, FloatArray(16_000), "pl", true) as Message.Reponse
        assertEquals(5L, r.id); assertEquals("16000 échantillons, pl", r.texte); assertEquals("traduit", r.traduction)
        m.arrete()
    }

    @Test fun `le processus qui tombe le dit`() {
        val m = faux()
        m.demarre()
        val e = runCatching { m.transcrit(1, FloatArray(1600), "crash", false) }.exceptionOrNull()
        assertTrue(e?.message, e?.message?.contains("code 3") == true && e.message!!.contains("panne simulée"))
        val m2 = faux("--refuse")
        val e2 = runCatching { m2.demarre() }.exceptionOrNull()
        assertEquals("modèle absent", e2?.message)
    }
}

/** A stand-in for satme_phonie.py, run as a child process by the tests. */
object FauxMoteurPhonie {
    @JvmStatic fun main(args: Array<String>) {
        if ("--refuse" in args) { println("""{"erreur":"modèle absent","fatal":true}"""); System.exit(2) }
        println("""{"pret":true,"modele":"faux","calcul":"cpu","type":"int8","charge_s":0.0}""")
        System.out.flush()
        val entree = java.io.DataInputStream(System.`in`.buffered())
        while (true) {
            val (h, pcm) = ProtocolePhonie.litTrame(entree) ?: return
            if (h.optString("langue") == "crash") { System.err.println("panne simulée"); System.err.flush(); System.exit(3) }
            val r = JSONObject().put("id", h.getLong("id")).put("langue", h.optString("langue")).put("proba", 1.0).put("calcul_s", 0.0)
                .put("segments", org.json.JSONArray().put(JSONObject().put("debut", 0).put("fin", 1).put("texte", "${pcm.size} échantillons, ${h.optString("langue")}")
                    .put("avg_logprob", -0.1).put("no_speech_prob", 0.0)))
                .put("traduction", if (h.optBoolean("traduire")) "traduit" else "")
            println(r.toString())
            System.out.flush()
        }
    }
}
