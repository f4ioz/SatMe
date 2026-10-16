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

    /** An engine translating segment by segment into the language asked (the second segment: a hallucination). */
    private class Traducteur : MoteurPhonie {
        val cibles = ArrayList<String>()
        override fun demarre() = Message.Pret("turbo", "cpu", "int8", 0.1, "")
        override fun transcrit(id: Long, pcm: FloatArray, langue: String, traduire: Boolean): Message = transcrit(id, pcm, langue, traduire, "")
        override fun transcrit(id: Long, pcm: FloatArray, langue: String, traduire: Boolean, cible: String): Message {
            cibles += cible
            val tr = traduire && cible.isNotEmpty()
            return Message.Reponse(id, "pl", 0.98, 0.2, listOf(
                ProtocolePhonie.Morceau(0.0, 2.0, "Dzień dobry, tu SP9ABC", -0.2, 0.01, 1.2, if (tr) "Bonjour, ici SP9ABC" else ""),
                ProtocolePhonie.Morceau(2.0, 3.0, "Dziękuję za obejrzenie", -0.2, 0.01, 1.2, if (tr) "Merci de votre attention" else "")),
                if (tr) "Bonjour, ici SP9ABC Merci de votre attention" else "", if (tr) cible else "")
        }
        override fun arrete() {}
    }

    @Test fun `la traduction en francais sous le texte`() {
        val t = Traducteur()
        val st = StockageMemoire()
        val p = PhoniePc(st, InstallPhoniePc(dossier), File(dossier, "phonie"), { horloge }, fabrique = { t }).also { ouverts += it }
        assertEquals("fr", p.cible)
        p.actif = true
        // Off: no language asked, no translation, the text as before.
        parle(p, 1)
        assertTrue(attend(3000) { p.bulles().size == 1 })
        assertEquals("", t.cibles.last())
        val b0 = p.bulles().single()
        assertEquals("Dzień dobry, tu SP9ABC", b0.texte); assertEquals("", b0.traduction); assertEquals("", b0.langueCible)
        // On: French, the hallucinated segment's translation left out with it.
        p.action("/api/phonie", mapOf("traduire" to "1"))
        parle(p, 1)
        assertTrue(attend(3000) { p.bulles().size == 2 })
        assertEquals("fr", t.cibles.last())
        val b = p.bulles().last()
        assertEquals("Dzień dobry, tu SP9ABC", b.texte); assertEquals("Bonjour, ici SP9ABC", b.traduction); assertEquals("fr", b.langueCible)
        assertTrue(p.texteJour(p.jours().single())!!.contains("(fr) Bonjour, ici SP9ABC"))
        assertEquals("fr", p.json(0).getJSONArray("bulles").getJSONObject(1).getString("langueCible"))
        // Whisper's English: no language in the header, as before.
        p.action("/api/phonie", mapOf("cible" to "whisper"))
        parle(p, 1)
        assertTrue(attend(3000) { t.cibles.size == 3 })
        assertEquals("", t.cibles.last())
        // A language not offered is refused; the DATV subtitles' choice is kept.
        p.action("/api/phonie", mapOf("cible" to "xx", "stTraduction" to "1"))
        assertEquals("whisper", p.cible); assertTrue(p.stTraduction)
        assertTrue(p.json(0).getBoolean("stTraduction"))
    }

    @Test fun `les paires de langues a installer`() {
        assertEquals(listOf("pl_en", "en_fr", "de_en"), InstallPhoniePc.pairesPour(listOf("pl", "de", "fr"), "fr"))
        assertEquals(listOf("en_fr"), InstallPhoniePc.pairesPour(listOf("en"), "fr"))
        assertEquals(listOf("pl_en"), InstallPhoniePc.pairesPour(listOf("pl", "en"), "en"))
        val i = InstallPhoniePc(dossier)
        assertTrue(i.pairesInstallees().isEmpty())
        File(i.traduction, "pl_en/model").mkdirs(); File(i.traduction, "pl_en/model/model.bin").writeText("x"); File(i.traduction, "pl_en/sentencepiece.model").writeText("x")
        File(i.traduction, "en_fr/model").mkdirs()
        assertEquals(listOf("pl_en"), i.pairesInstallees())
        assertTrue(i.commande("python3", "turbo", "auto").containsAll(listOf("--traduction", i.traduction.absolutePath)))
        // The settings card: what is there, what is missing and its size.
        val p = phonie(Faux("x"))
        val tr = p.jsonMoteur().getJSONObject("traduction")
        assertEquals("fr", tr.getString("cible"))
        assertTrue(tr.getJSONArray("manquent").toList().contains("en_fr"))
        assertFalse(tr.getJSONArray("manquent").toList().contains("pl_en"))
        p.action("/api/phonie/moteur", mapOf("tradLangues" to "pl,zz"))
        assertEquals(listOf("pl"), p.languesTraduction)
        assertEquals(65, p.jsonMoteur().getJSONObject("traduction").getInt("mo"))
        assertTrue(i.retirePaire("pl_en")); assertFalse(i.retirePaire("../x"))
        assertTrue(i.pairesInstallees().isEmpty())
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
        val f = m.transcrit(6, FloatArray(1600), "pl", true, "fr") as Message.Reponse
        assertEquals("fr", f.langueCible); assertEquals("traduit en fr", f.morceaux.single().traduction)
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
                    .put("avg_logprob", -0.1).put("no_speech_prob", 0.0)
                    .apply { if (h.has("cible")) put("traduction", "traduit en " + h.getString("cible")) }))
                .put("traduction", if (h.optBoolean("traduire")) "traduit" else "")
                .apply { if (h.has("cible")) put("langue_cible", h.getString("cible")) }
            println(r.toString())
            System.out.flush()
        }
    }
}
