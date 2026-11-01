/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.ActivitesEnLigne
import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.domain.ActivitesAVenir
import fr.f4ioz.satcombo.domain.CampagneSstv
import fr.f4ioz.satcombo.domain.PiloteSstv
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The SAKHACUBE-CHOLBON campaign of 1 Nov 2026 (Paris, a week after the end of
 * summer time): its period from an activity announced by days, edited with a
 * time of its own, deleted for good, one pass cancelled; the calendar's route;
 * an appointment typed and changed.
 */
class CampagneCalendrierPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-calendrier-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    private val paris = ZoneId.of("Europe/Paris")
    private val m = 60_000L
    private fun ici(s: String) = LocalDateTime.parse(s).atZone(paris).toInstant().toEpochMilli()
    private fun utc(s: String) = LocalDateTime.parse(s).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
    private var heure = ici("2026-10-30T12:00")

    private fun passe(aos: Long, el: Double = 28.0) = SatPass("SAKHACUBE-CHOLBON", 67290, aos, aos + 10 * m, el, 0.0, 0.0, false, false)
    private val passes = listOf(passe(ici("2026-10-31T09:43")), passe(ici("2026-10-31T20:46"), 40.0), passe(ici("2026-11-01T21:52")),
        passe(ici("2026-11-01T23:25")), passe(ici("2026-11-02T01:00")))

    /** « à partir du 29 octobre », no time: read by days (29/10 → 31/10 here, as a post would give 29–31). */
    private val sakha = ActivitesAVenir.Activite(id = "rss:https://site.amsat-f.org/2026/10/29/emission-sstv-sakhacube", type = ActivitesAVenir.Type.SSTV,
        satNom = "SAKHACUBE-CHOLBON", catnum = 67290, debutMs = utc("2026-10-29T00:00"), finMs = utc("2026-10-31T23:59"),
        precision = ActivitesAVenir.Precision.JOURS, freqHz = 437_100_000L, titre = "Emission SSTV – SAKHACUBE-CHOLBON (RS18S) a partir du du 29 octobre 2026",
        sources = listOf(ActivitesAVenir.Source("AMSAT-F")))

    private class Fausse : PiloteSstv.Station {
        val gestes = ArrayList<String>()
        override fun emission(): String? = null
        override fun conflit(c: CampagneSstv.Campagne): String? = null
        override fun capture() = emptyMap<String, String>()
        override fun prepare(c: CampagneSstv.Campagne, p: SatPass) = PiloteSstv.Preparation(emptyList()).also { gestes += "prepare " + p.aosEpochMs }
        override fun enregistre(c: CampagneSstv.Campagne): String? = null
        override fun arreteEnregistrement(): String = "a.wav"
        override fun images(deMs: Long, aMs: Long) = listOf("SatMe_SSTV_1.png", "SatMe_SSTV_2.png")
        override fun parque(): String? = null
        override fun restaure(avant: Map<String, String>): List<String> = emptyList()
        override fun annonce(texte: String) {}
    }

    private data class Montage(val st: StationPc, val ap: AutoPiloteSstvPc, val act: ActivitesPc, val cal: CalendrierPc, val f: Fausse)

    private fun monte(): Montage {
        val st = StationPc(StockageMemoire(), Catalogue(File(dossier, "cache"), enLigne = false), PortsSeriePc(), portee, { heure })
        val pas = { n: Int, de: Long, a: Long -> if (n == 67290) passes.filter { it.losEpochMs > de && it.aosEpochMs < a }
            else listOf(SatPass("RS-44", 44909, ici("2026-11-02T10:00"), ici("2026-11-02T10:15"), 55.0, 0.0, 0.0, false, false)).filter { it.losEpochMs > de && it.aosEpochMs < a } }
        val f = Fausse()
        val ap = AutoPiloteSstvPc(st, dossier, { heure }, pas, stationPilote = f, zone = { paris })
        val faux = ActivitesEnLigne.Telechargeur { _, _, _ -> ActivitesEnLigne.Reponse(404, null) }
        val act = ActivitesPc(st, dossier, { heure }, faux, pas, fictives = { listOf(sakha) }, catalogue = { listOf("SAKHACUBE-CHOLBON" to 67290) }, zone = { paris })
        act.autopilote = ap
        return Montage(st, ap, act, CalendrierPc(st, { ap }, { act }, { heure }, pas, { paris }), f)
    }

    private fun postAp(ap: AutoPiloteSstvPc, route: String, p: Map<String, String>) =
        JSONObject(String(ap.sert("/api/autopilote$route", "POST", { p[it].orEmpty() }) { null }.corps, Charsets.UTF_8))
    private fun getAp(ap: AutoPiloteSstvPc) = JSONObject(String(ap.sert("/api/autopilote", "GET", { "" }) { null }.corps, Charsets.UTF_8))
    private fun activite(a: ActivitesPc) = JSONObject(String(a.sert("/api/activites", "GET") { "" }.corps, Charsets.UTF_8))
        .getJSONArray("activites").let { l -> (0 until l.length()).map { l.getJSONObject(it) }.first { it.getString("id") == sakha.id } }
    private fun active(a: ActivitesPc) = JSONObject(String(a.sert("/api/activites/autopilote", "POST") { k -> mapOf("id" to sakha.id)[k].orEmpty() }.corps, Charsets.UTF_8))

    @Test fun `les jours annonces sont ceux de la campagne, sans un jour de trop`() {
        val (_, ap, act) = monte()
        act.tic()
        assertTrue(active(act).getBoolean("ok"))
        val c = ap.campagnes.single()
        assertEquals(ici("2026-10-29T00:00"), c.debutMs)
        // 31/10 23:59 here — not 01/11 23:59 (23:59 UTC read as « the next day » in Paris, as before).
        assertEquals(ici("2026-10-31T23:59"), c.finMs)
        assertTrue(c.aConfirmer)
        val j = getAp(ap).getJSONArray("campagnes").getJSONObject(0)
        assertEquals("du jeu. 29/10 00:00 au sam. 31/10 23:59 (heure de Paris)", j.getString("periode"))
        // The activity's card says the same days; « Préparer » fills the form with them.
        val jours = activite(act).getJSONObject("jours")
        assertEquals(c.debutMs, jours.getLong("de")); assertEquals(c.finMs, jours.getLong("a"))
        val prop = JSONObject(String(act.sert("/api/activites/campagne", "GET") { k -> mapOf("id" to sakha.id)[k].orEmpty() }.corps, Charsets.UTF_8)).getJSONObject("proposition")
        assertEquals(c.finMs, prop.getLong("a"))
    }

    @Test fun `l'heure tapee est gardee telle quelle, la campagne finie ne prevoit plus rien`() {
        val (_, ap, act) = monte()
        act.tic(); active(act)
        val c = ap.campagnes.single()
        // The owner wants it over at 01/11 22:00, « journées entières » still ticked: kept as typed (the page used to put 23:59 back).
        val form = mapOf("id" to c.id, "nom" to c.nom, "catnum" to "67290", "freq" to "437,100", "mode" to "Auto", "de" to c.debutMs.toString(),
            "a" to ici("2026-11-01T22:00").toString(), "aconfirmer" to "1", "el" to "10")
        assertTrue(postAp(ap, "/campagne", form).getBoolean("ok"))
        val k = ap.campagnes.single()
        assertEquals(ici("2026-11-01T22:00"), k.finMs)
        assertFalse(k.activiteSuit)
        heure = ici("2026-11-01T21:00")
        val j = getAp(ap).getJSONArray("campagnes").getJSONObject(0)
        // In the editor: the passes left, 21:52 to come, not 23:25.
        val ps = j.getJSONArray("passages")
        assertEquals(listOf(ici("2026-11-01T21:52")), (0 until ps.length()).map { ps.getJSONObject(it).getLong("aos") })
        assertFalse(j.getBoolean("terminee"))
        // After 22:00: « terminée », nothing planned (and the activity refreshed does not move it back).
        heure = ici("2026-11-01T22:30")
        act.demandeRafraichissement(); act.tic()
        val j2 = getAp(ap)
        assertTrue(j2.getJSONArray("campagnes").getJSONObject(0).getBoolean("terminee"))
        assertEquals(0, j2.getJSONArray("campagnes").getJSONObject(0).getInt("nb"))
        assertEquals(0, j2.getJSONArray("prochains").length())
        assertEquals(ici("2026-11-01T22:00"), ap.campagnes.single().finMs)
    }

    @Test fun `une campagne supprimee le reste, l'activite la dit supprimee et peut la recreer`() {
        val (_, ap, act) = monte()
        act.tic(); active(act)
        val id = ap.campagnes.single().id
        assertFalse(activite(act).getBoolean("campagneSupprimee"))
        val r = postAp(ap, "/retire", mapOf("id" to id))
        assertTrue(r.toString(), r.getBoolean("ok"))
        assertTrue(ap.campagnes.isEmpty())
        // The activities read again (the sources' refresh, the period followed): nothing comes back.
        repeat(3) { heure += 6 * m; act.demandeRafraichissement(); act.tic() }
        assertTrue(ap.campagnes.isEmpty())
        val a = activite(act)
        assertTrue(a.isNull("lienAp"))
        assertTrue(a.getBoolean("campagneSupprimee"))
        // A new start on the same folder: still deleted.
        val (_, ap2, act2) = monte()
        act2.tic()
        assertTrue(ap2.campagnes.isEmpty())
        assertTrue(activite(act2).getBoolean("campagneSupprimee"))
        // Deleting what is not there says so.
        assertFalse(postAp(ap2, "/retire", mapOf("id" to id)).getBoolean("ok"))
        // « Recréer la campagne », on purpose.
        assertTrue(active(act2).getBoolean("ok"))
        assertEquals(1, ap2.campagnes.size)
        assertFalse(activite(act2).getBoolean("campagneSupprimee"))
    }

    @Test fun `un seul passage annule, les autres recus`() {
        val (_, ap, act, _, f) = monte()
        act.tic(); active(act)
        val c = ap.campagnes.single()
        heure = ici("2026-10-31T09:00")
        val premier = ici("2026-10-31T09:43")
        val r = postAp(ap, "/annule", mapOf("id" to c.id, "aos" to premier.toString(), "on" to "1"))
        assertTrue(r.toString(), r.getBoolean("ok"))
        val plan = ap.plan()
        assertEquals(CampagneSstv.Statut.ANNULE, plan.first { it.passage.aosEpochMs == premier }.statut)
        assertEquals(CampagneSstv.Statut.PREVU, plan.first { it.passage.aosEpochMs == ici("2026-10-31T20:46") }.statut)
        assertTrue(ap.campagnes.single().active)
        // Its time comes: nothing set up; the next one is.
        heure = premier - m; ap.tic()
        assertTrue(f.gestes.isEmpty())
        heure = ici("2026-10-31T20:45"); ap.tic()
        assertEquals(listOf("prepare " + ici("2026-10-31T20:46")), f.gestes)
        // Taken again.
        assertTrue(postAp(ap, "/annule", mapOf("id" to c.id, "aos" to premier.toString(), "on" to "0")).getBoolean("ok"))
        assertTrue(ap.campagnes.single().annules.isEmpty())
        assertFalse(postAp(ap, "/annule", mapOf("id" to "inconnue", "aos" to premier.toString())).getBoolean("ok"))
    }

    @Test fun `le calendrier rend les rendez-vous, campagnes, passages et activites d'une periode`() {
        val (st, ap, act, cal) = monte()
        act.tic(); active(act)
        val c = ap.campagnes.single()
        // A pass received (two pictures), one cancelled; then the calendar on 1 Nov.
        heure = ici("2026-10-31T20:45"); ap.tic()
        heure = ici("2026-10-31T20:57"); ap.tic()
        assertEquals(CampagneSstv.Statut.FAIT, ap.historique.single().statut)
        heure = ici("2026-10-31T21:00")
        st.favori(44909, true)
        assertTrue(st.gardeAgenda(0, "Sked QF4AAA", ici("2026-11-02T08:00"), ici("2026-11-02T08:20"), "RS-44", "SKED", "", 10, 0).isSuccess)
        val de = ici("2026-10-26T00:00"); val a = ici("2026-11-09T00:00")
        val j = JSONObject(String(cal.sert { k -> mapOf("de" to de.toString(), "a" to a.toString())[k].orEmpty() }.corps, Charsets.UTF_8))
        assertTrue(j.toString(), j.getBoolean("ok"))
        assertEquals("heure de Paris", j.getString("zone"))
        val l = j.getJSONArray("items").let { x -> (0 until x.length()).map { x.getJSONObject(it) } }
        val camp = l.single { it.getString("sorte") == "campagne" }
        assertEquals(c.id, camp.getString("id")); assertEquals(1, camp.getInt("recus")); assertEquals(2, camp.getInt("images"))
        val ps = l.filter { it.getString("sorte") == "passage" && it.getString("source") == "campagne" }
        fun statut(aos: Long) = ps.single { it.getLong("aos") == aos }.getString("statut")
        assertEquals("MANQUE", statut(ici("2026-10-31T09:43")))     // gone, no trace (the activity was switched on later)
        assertEquals("FAIT", statut(ici("2026-10-31T20:46")))
        assertEquals(2, ps.single { it.getLong("aos") == ici("2026-10-31T20:46") }.getInt("images"))
        // The period ends 31/10 23:59 here: the 1 Nov passes are not the campaign's.
        assertTrue(ps.none { it.getLong("aos") >= ici("2026-11-01T00:00") })
        val fav = l.filter { it.getString("sorte") == "passage" && it.getString("source") == "favori" }
        assertEquals(listOf(44909), fav.map { it.getInt("catnum") })
        assertEquals(55, fav.single().getInt("el"))
        val rdv = l.single { it.getString("sorte") == "agenda" }
        assertEquals("Sked QF4AAA", rdv.getString("titre"))
        val acti = l.single { it.getString("sorte") == "activite" }
        assertEquals(c.id, acti.getString("campagne"))
        assertEquals(c.finMs, acti.getLong("a"))
        // Too long a range: refused.
        assertFalse(JSONObject(String(cal.sert { k -> mapOf("de" to de.toString(), "a" to (de + 50 * 86_400_000L).toString())[k].orEmpty() }.corps, Charsets.UTF_8)).getBoolean("ok"))
        // Deleted: the activity says so in the calendar too.
        ap.retire(c.id)
        val l2 = cal.json(de, a).getJSONArray("items").let { x -> (0 until x.length()).map { x.getJSONObject(it) } }
        assertTrue(l2.single { it.getString("sorte") == "activite" }.getBoolean("campagneSupprimee"))
        assertTrue(l2.none { it.getString("sorte") == "campagne" })
    }

    @Test fun `un rendez-vous tape, modifie, supprime, ses instants gardes de part et d'autre du changement d'heure`() {
        val st = monte().st
        // 25/10 02:30 local happens twice (03:00 summer → 02:00 winter): the browser sends the instant; kept as is.
        val avant = utc("2026-10-25T00:30"); val apres = utc("2026-10-25T01:30")
        val e = st.gardeAgenda(0, "SSTV ISS", avant, apres, "ISS", "sstv", "note", 30, 145_800_000L).getOrThrow()
        assertEquals("SSTV", e.kind)
        val k = st.etat.value.agenda.single()
        assertEquals(avant, k.timeMs); assertEquals(apres, k.endMs)
        // Changed: same id, its reminder's state kept.
        st.activeAgenda(k.id)
        val e2 = st.gardeAgenda(k.id, "SSTV ISS (série 35)", ici("2026-10-24T20:00"), ici("2026-10-26T20:00"), "ISS", "SSTV", "", 60, 0).getOrThrow()
        assertEquals(k.id, e2.id)
        val k2 = st.etat.value.agenda.single()
        assertEquals("SSTV ISS (série 35)", k2.title)
        assertFalse(k2.enabled)
        assertEquals(utc("2026-10-24T18:00"), k2.timeMs)   // summer time, UTC+2
        assertEquals(utc("2026-10-26T19:00"), k2.endMs)    // winter time, UTC+1
        assertTrue(st.gardeAgenda(k.id, "x", ici("2026-10-26T20:00"), ici("2026-10-24T20:00"), "", "", "", 0, 0).isFailure)
        assertTrue(st.gardeAgenda(424242, "x", ici("2026-10-26T20:00"), 0, "", "", "", 0, 0).isFailure)
        st.retireAgenda(k.id)
        assertTrue(st.etat.value.agenda.isEmpty())
        assertNull(st.etat.value.agenda.firstOrNull())
    }
}
