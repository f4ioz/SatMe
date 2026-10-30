/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.datv.EtatDatv
import fr.f4ioz.satcombo.datv.ReglageDatv
import fr.f4ioz.satcombo.station.CatalogueMateriel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/** « Direct sur le site »: what is built, what goes (and only that), when it stops. */
class DirectSitePcTest {

    @get:Rule val tmp = TemporaryFolder()

    private class Envoi(val methode: String, val url: String, val entetes: Map<String, String>, val corps: ByteArray?)
    private val envois = ArrayList<Envoi>()
    private var reponse: (Envoi) -> Pair<Int, String> = { 200 to "{\"ok\":true}" }
    private var t = 1_800_000_000_000L

    /** The settings of the last [direct]. */
    private lateinit var reglages: StockageFichier

    private fun direct(): Pair<SitePc, DirectSitePc> {
        val st = StockageFichier(tmp.newFile("r-" + System.nanoTime() + ".properties"))
        reglages = st
        val site = SitePc(st, tmp.newFolder(), { false }, { t }, SitePc.Transport { m, u, e, c -> val x = Envoi(m, u, e, c?.octets()); envois += x; reponse(x) })
        // « Mon site web » switched on, the site set and tested.
        fr.f4ioz.satcombo.demo.ModulesStation.pose(st, emptySet())
        site.sert("/api/site/essai", "POST") { k -> when (k) { "url" -> "https://example.org/api/publication/{type}"; "jeton" -> "le-jeton"; else -> "" } }
        envois.clear()
        val d = DirectSitePc(st, site, { t })
        site.direct = d
        return site to d
    }

    /** What the auto-pilot's page carries, with things the site must never get. */
    private fun ap() = JSONObject().put("maitre", true).put("cartes", "hw:CARD=USB,DEV=0").put("profils", "icom-9700")
        .put("prochain", JSONObject().put("campagne", "ARISS").put("sat", "ISS").put("aos", t + 3_600_000L).put("los", t + 4_200_000L).put("el", 43)
            .put("freqHz", 145_800_000L).put("rotor", "192.168.0.9:4533"))
        .put("enCours", JSONObject().put("campagne", "ARISS").put("sat", "ISS").put("aos", t - 60_000L).put("los", t + 500_000L).put("el", 60).put("phase", "EN_COURS")
            .put("mode", "PD120").put("images", 2).put("enregistrement", "/home/olivier/.satme/son.wav")
            .put("image", JSONObject().put("phase", "ATTENTE").put("prochaine", t + 90_000L).put("progression", 0.5)))

    private fun jpeg(w: Int, h: Int): ByteArray {
        val i = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) i.setRGB(x, y, (x * 31 + y * 17) * 2654435761L.toInt())
        return ByteArrayOutputStream().also { ImageIO.write(i, "jpg", it) }.toByteArray()
    }

    @Test fun `the auto-pilot's words are only names, times and counts`() {
        val o = DirectSitePc.pilote(ap(), Triple("ISS", t - 600_000L, "PD120"), t)
        val texte = o.toString()
        for (secret in listOf("hw:CARD", "icom", "192.168", "4533", ".satme", "son.wav", "145800000", "freqHz", "rotor", "enregistrement", "cartes", "profils"))
            assertFalse(secret, texte.contains(secret))
        assertEquals(setOf("maitre", "prochain", "enCours", "derniere"), o.keySet())
        assertEquals(setOf("campagne", "sat", "aos", "los", "el"), o.getJSONObject("prochain").keySet())
        assertEquals(t + 90_000L, o.getJSONObject("enCours").getJSONObject("image").getLong("prochaine"))
    }

    @Test fun `the receiver's figures, never its node`() {
        val e = EtatDatv(actif = true, etat = 4, merDb = 7.43, modcod = "QPSK 4/5", service = "F4XYZ", fournisseur = "Paris", srTrouveKs = 333)
        val o = DirectSitePc.datv(e, ReglageDatv(cielKhz = 10_494_750L, srKs = 333), 2.1)
        assertEquals(setOf("verrouille", "cielKhz", "srKs", "service", "fournisseur", "modcod", "mer", "marge"), o.keySet())
        assertEquals(7.4, o.getDouble("mer"), 1e-9)
        val hors = DirectSitePc.datv(EtatDatv(actif = true, etat = 1), ReglageDatv(), null)
        assertFalse(hors.getBoolean("verrouille")); assertFalse(hors.has("service"))
    }

    private val fiche = fr.f4ioz.satcombo.domain.QrzReponse.Fiche(indicatif = "G4ABC", carre = "IO93FS", nom = "John Smith", prenom = "John", qth = "Leeds",
        courriel = "john@example.org", pays = "England", image = "https://cdn-xml.qrz.com/c/g4abc/photo.jpg", lat = 53.80123, lon = -1.55456,
        adresse = "1 Example Street", etat = "West Yorkshire")
    private val paris = fr.f4ioz.satcombo.data.Observer(48.85, 2.35)

    @Test fun `the DATV station watched - its card, never its photo, mail or street`() {
        val o = DirectSitePc.stationDatvJson(DirectSitePc.StationDatv("G4ABC", true, fiche, paris))!!
        assertTrue(DirectSitePc.CLES_STATION_DATV.containsAll(o.keySet()))
        assertEquals("G4ABC", o.getString("indicatif")); assertTrue(o.getBoolean("selection"))
        assertEquals("John Smith", o.getString("nom")); assertEquals("Leeds", o.getString("qth"))
        assertEquals("IO93fs", o.getString("carre")); assertEquals("England", o.getString("pays"))
        // Paris to Leeds: some 600 km, to the north-west.
        assertTrue(o.getLong("km") in 550..700); assertTrue(o.getLong("azimut") in 300..350)
        val texte = o.toString()
        for (secret in listOf("qrz.com", "photo", "example.org", "Street", "53.8", "1.55", "48.85", "Yorkshire")) assertFalse(secret, texte.contains(secret))
        // Not found in QRZ (or no account): the callsign alone; not a callsign: nothing.
        assertEquals(setOf("indicatif", "selection"), DirectSitePc.stationDatvJson(DirectSitePc.StationDatv("F4XYZ", false, null, paris))!!.keySet())
        assertNull(DirectSitePc.stationDatvJson(DirectSitePc.StationDatv("QO-100", true, null, null)))
        // A wrong square is left out, and the distance with it (no coordinates either way without our place).
        val sans = DirectSitePc.stationDatvJson(DirectSitePc.StationDatv("G4ABC", false, fiche.copy(carre = "ZZ99"), null))!!
        assertFalse(sans.has("carre")); assertFalse(sans.has("km"))
    }

    @Test fun `the DATV figures carry the station - selected before the lock, confirmed once locked`() {
        val st = DirectSitePc.StationDatv("G4ABC", true, fiche, paris)
        val avant = DirectSitePc.datv(EtatDatv(actif = true, etat = 1), ReglageDatv(cielKhz = 10_494_750L, srKs = 333), null, st)
        assertEquals("G4ABC", avant.getJSONObject("station").getString("indicatif"))
        // Confirmed by the reception (not selected): only once locked.
        val nonSel = DirectSitePc.StationDatv("G4ABC", false, fiche, paris)
        assertFalse(DirectSitePc.datv(EtatDatv(actif = true, etat = 1), ReglageDatv(), null, nonSel).has("station"))
        val e = EtatDatv(actif = true, etat = 4, merDb = 7.4, modcod = "QPSK 4/5", service = "G4ABC", srTrouveKs = 333)
        assertTrue(DirectSitePc.datv(e, ReglageDatv(), 2.0, nonSel).has("station"))
        assertFalse(DirectSitePc.datv(e, ReglageDatv(), 2.0).has("station"))
    }

    @Test fun `the auto scan for the site - which signal, the countdown, the last switch, nothing else`() {
        val m = fr.f4ioz.satcombo.datv.SecoursDatv(fr.f4ioz.satcombo.datv.SecoursDatv.Reglages(actif = true, mode = fr.f4ioz.satcombo.datv.SecoursDatv.Mode.TOURNE, dureeS = 30))
        val iz = fr.f4ioz.satcombo.datv.SecoursDatv.Cible(10_494_750L, 333, "IZ0ZWS")
        val ik = fr.f4ioz.satcombo.datv.SecoursDatv.Cible(10_497_000L, 333, "IK5EHI")
        val l = listOf(iz, ik).map { fr.f4ioz.satcombo.datv.SecoursDatv.Candidat(it, fr.f4ioz.satcombo.datv.SecoursDatv.Origine.SPECTRE, 20_000, 0L, true) }
        var t = 1_800_000_000_000L
        fun rx(c: fr.f4ioz.satcombo.datv.SecoursDatv.Cible) = fr.f4ioz.satcombo.datv.SecoursDatv.Reception(true, true, true, c.khz, c.srKs)
        m.tic(t, rx(iz), l, true)
        t += 30_000L
        m.accorde(t, m.tic(t, rx(iz), l, true)!!)
        t += 7_000L; m.tic(t, rx(ik), l, true)
        val e = EtatDatv(actif = true, etat = 4, merDb = 7.4, service = "IK5EHI", srTrouveKs = 333)
        val b = DirectSitePc.datv(e, ReglageDatv(cielKhz = 10_497_000L, srKs = 333), 2.0, null, m.json(t)).getJSONObject("balayage")
        assertEquals(DirectSitePc.CLES_BALAYAGE.getValue(""), b.keySet())
        assertTrue(b.getBoolean("on")); assertEquals("tourne", b.getString("mode"))
        assertEquals(23L, b.getLong("resteS"))
        assertEquals("IK5EHI", b.getJSONObject("station").getString("nom")); assertEquals(10_497_000L, b.getJSONObject("station").getLong("khz"))
        assertEquals("IZ0ZWS", b.getJSONObject("prochain").getString("nom"))
        val k = b.getJSONObject("bascule")
        assertEquals(DirectSitePc.CLES_BALAYAGE.getValue("bascule"), k.keySet())
        assertEquals(t - 7_000L, k.getLong("t"))
        assertEquals("IZ0ZWS", k.getJSONObject("de").getString("nom")); assertEquals("IK5EHI", k.getJSONObject("vers").getString("nom"))
        for (s in listOf(b.getJSONObject("station"), b.getJSONObject("prochain"), k.getJSONObject("de"), k.getJSONObject("vers")))
            assertTrue(DirectSitePc.CLES_BALAYAGE.getValue("signal").containsAll(s.keySet()))
        // Never the settings, the reasons, the candidates.
        for (secret in listOf("écoulées", "candidats", "attente", "pause", "favori", "raison", "journal")) assertFalse(secret, b.toString().contains(secret))
        // Off: nothing sent; « until it drops »: no countdown, no next.
        assertFalse(DirectSitePc.datv(e, ReglageDatv(), 2.0, null, JSONObject().put("on", false)).has("balayage"))
        assertFalse(DirectSitePc.datv(e, ReglageDatv(), 2.0).has("balayage"))
        m.reglages = m.reglages.copy(mode = fr.f4ioz.satcombo.datv.SecoursDatv.Mode.COUPURE)
        val c = DirectSitePc.balayageJson(m.json(t))!!
        assertFalse(c.has("resteS")); assertFalse(c.has("prochain")); assertTrue(c.has("bascule"))
    }

    @Test fun `a spectrum line is small, scaled and the right way round`() {
        val db = FloatArray(16384) { -100f }.also { it[100] = -40f }
        val l = DirectSitePc.ligneSpectre(db, 10_489_750_000.0, -1_058_400.0)!!
        val b = Base64.getDecoder().decode(l.getString("bins"))
        assertEquals(1024, b.size)
        assertEquals(1_058_400L, l.getLong("largeurHz"))
        // Turned over: the peak near the start of the panorama ends near the end of the line.
        assertTrue((b[1023 - 100 / 16].toInt() and 0xFF) > 240)
        assertTrue((b[0].toInt() and 0xFF) < 40)
        assertTrue(l.toString().length < 1600)
        assertNull(DirectSitePc.ligneSpectre(FloatArray(10), 10_489_750_000.0, 1e6))
    }

    @Test fun `a DATV picture is made small`() {
        val j = DirectSitePc.jpegReduit(jpeg(1920, 1080))!!
        assertTrue(j.size <= 190 * 1024)
        assertEquals(640, ImageIO.read(j.inputStream()).width)
        assertNull(DirectSitePc.jpegReduit(ByteArray(100)))
    }

    @Test fun `nothing goes unless chosen, then each part to its route with the token`() {
        val (site, d) = direct()
        val a = ap()
        d.pilote = { a }
        d.panorama = { DirectSitePc.Panorama(FloatArray(16384) { -90f }, 10_489_750_000.0, 1_058_400.0) }
        var demande = 0
        d.demandePanorama = { demande++ }
        d.tic()
        assertTrue(envois.isEmpty())
        assertEquals(0, demande)
        site.sert("/api/site/direct", "POST") { k -> when (k) { "on_pilote", "on_spectre" -> "1"; else -> "" } }
        d.tic()
        assertEquals(listOf("https://example.org/api/publication/direct", "https://example.org/api/publication/direct-spectre"), envois.map { it.url })
        assertTrue(envois.all { it.entetes["Authorization"] == "Bearer le-jeton" && it.methode == "POST" && it.entetes["X-SatMe-Version"] == "1" })
        assertEquals(listOf("direct", "direct-spectre"), envois.map { it.entetes["X-SatMe-Type"] })
        assertTrue(demande > 0)
        val corps = String(envois[0].corps!!)
        assertFalse(corps.contains("le-jeton")); assertFalse(corps.contains("hw:CARD"))
        // The same auto-pilot state again within a minute: not sent; the spectrum every second.
        envois.clear(); t += 10_000L; d.tic()
        assertEquals(listOf("https://example.org/api/publication/direct-spectre"), envois.map { it.url })
        // The token is never shown on SatMe-PC's page either.
        assertFalse(site.json().toString().contains("le-jeton"))
        assertTrue(site.json().getJSONObject("direct").getJSONArray("canaux").getJSONObject(0).getBoolean("on"))
    }

    @Test fun `the beacons - in direct as balises, on a verdict's change, else every period, null once when off`() {
        val (site, d) = direct()
        var b: JSONObject? = JSONObject().put("large", JSONObject().put("statut", "ok").put("cause", "").put("texte", "Balise reçue (MER 8,0 dB)").put("mer", 8.0))
        d.balises = { b }
        d.tic()
        assertTrue(envois.isEmpty())
        site.sert("/api/site/direct", "POST") { k -> if (k == "on_balises") "1" else "" }
        d.tic()
        assertEquals(1, envois.size)
        val o = JSONObject(String(envois[0].corps!!))
        assertEquals("direct", envois[0].entetes["X-SatMe-Type"])
        assertEquals("ok", o.getJSONObject("balises").getJSONObject("large").getString("statut"))
        // The words change (another MER), the verdict does not: not before the period (5 min).
        b = JSONObject().put("large", JSONObject().put("statut", "ok").put("cause", "").put("texte", "Balise reçue (MER 8,2 dB)").put("mer", 8.2))
        repeat(20) { t += 10_000L; d.tic() }
        assertEquals(1, envois.size)
        // The verdict changes: at once (within 10 s).
        b = JSONObject().put("large", JSONObject().put("statut", "flux-abime").put("cause", "source").put("texte", "Flux abîmé à la source"))
        t += 10_000L; d.tic()
        assertEquals(2, envois.size)
        // Unchanged: again after the period.
        t += 300_000L; d.tic()
        assertEquals(3, envois.size)
        // The watch switched off: null once, then nothing.
        b = null
        t += 10_000L; d.tic()
        assertEquals(4, envois.size)
        assertTrue(JSONObject(String(envois[3].corps!!)).isNull("balises"))
        t += 400_000L; d.tic()
        assertEquals(4, envois.size)
        assertTrue(d.json().toString().contains("Balises QO-100"))
    }

    @Test fun `nothing at all while Mon site web is switched off, even the parts chosen`() {
        val (site, d) = direct()
        d.pilote = { ap() }
        d.panorama = { DirectSitePc.Panorama(FloatArray(16384) { -90f }, 10_489_750_000.0, 1_058_400.0) }
        var demande = 0
        d.demandePanorama = { demande++ }
        site.sert("/api/site/direct", "POST") { k -> when (k) { "on_pilote", "on_spectre", "on_datv" -> "1"; else -> "" } }
        fr.f4ioz.satcombo.demo.ModulesStation.pose(reglages, setOf(fr.f4ioz.satcombo.demo.ModulesStation.SITE))
        repeat(5) { t += 10_000L; d.tic() }
        assertTrue(envois.isEmpty())
        assertEquals(0, demande)
        assertNull(site.envoieDirect("direct", JSONObject()))
        // The page cannot switch a part on or wipe the site's live block either.
        assertFalse(site.sert("/api/site/direct", "POST") { k -> if (k == "efface") "1" else "" }.corps.toString(Charsets.UTF_8).contains("\"ok\":true"))
        assertTrue(envois.isEmpty())
    }

    @Test fun `a refusal waits two minutes and says why, a silent site backs off`() {
        val (site, d) = direct()
        d.panorama = { DirectSitePc.Panorama(FloatArray(4096) { -90f }, 10_489_750_000.0, 1_058_400.0) }
        site.sert("/api/site/direct", "POST") { k -> if (k == "on_spectre") "1" else "" }
        reponse = { 403 to "{\"ok\":false,\"refuse\":true,\"erreur\":\"le site n'affiche pas le spectre\"}" }
        d.tic()
        assertEquals(1, envois.size)
        assertTrue(d.json().toString().contains("refusé par le site : le site n'affiche pas le spectre"))
        t += 60_000L; d.tic()
        assertEquals(1, envois.size)
        t += 61_000L; d.tic()
        assertEquals(2, envois.size)
        // No answer: 30 s, then a minute.
        reponse = { throw java.io.IOException("connexion refusée") }
        t += 121_000L; d.tic()
        assertEquals(3, envois.size)
        assertTrue(d.json().toString().contains("pas de réponse : connexion refusée"))
        t += 10_000L; d.tic()
        assertEquals(3, envois.size)
        t += 21_000L; d.tic()
        assertEquals(4, envois.size)
        t += 31_000L; d.tic()
        assertEquals(4, envois.size)
    }

    @Test fun `the DATV picture goes only while locked, and once`() {
        val (site, d) = direct()
        val img = jpeg(1280, 720)
        var e = EtatDatv(actif = true, etat = 1)
        d.datv = { DirectSitePc.Datv(e, ReglageDatv(), null, img) }
        site.sert("/api/site/direct", "POST") { k -> if (k == "on_datv") "1" else "" }
        d.tic()
        assertEquals(listOf("https://example.org/api/publication/direct"), envois.map { it.url })
        e = EtatDatv(actif = true, etat = 4, merDb = 6.0, service = "F4XYZ")
        t += 5_000L; d.tic()
        assertEquals("https://example.org/api/publication/direct-datv-image", envois.last().url)
        assertTrue(envois.last().entetes["Content-Type"]!!.startsWith("multipart/form-data"))
        assertNotNull(ImageIO.read(SitePcTest.parties(envois.last().entetes["Content-Type"]!!, envois.last().corps!!).getValue("fichier").second.inputStream()))
        val n = envois.size
        t += 5_000L; d.tic()
        assertEquals(n + 1, envois.size)
        // The receiver stopped: told once, then nothing.
        e = EtatDatv(actif = false)
        t += 5_000L; d.tic(); t += 5_000L; d.tic()
        assertEquals(n + 2, envois.size)
        assertTrue(String(envois.last().corps!!).contains("\"datv\":null"))
    }

    @Test fun `during a pass the picture goes every few seconds, behind Auto-pilote, and only when it changed`() {
        val (site, d) = direct()
        val ap = JSONObject().put("enCours", JSONObject().put("sat", "ISS").put("phase", "EN_COURS").put("aos", t - 60_000L).put("los", t + 300_000L)
            .put("elMaintenant", 31).put("azMaintenant", 120))
        d.pilote = { ap }
        var image = jpeg(320, 256)
        d.imageSstv = { image }
        d.tic()
        assertTrue(envois.isEmpty())
        site.sert("/api/site/direct", "POST") { k -> if (k == "on_pilote") "1" else "" }
        d.tic()
        assertEquals(listOf("https://example.org/api/publication/direct", "https://example.org/api/publication/direct-sstv-image"), envois.map { it.url })
        assertTrue(String(envois[0].corps!!).contains("\"elMaintenant\":31"))
        t += DirectSitePc.PERIODE_SSTV_MS; d.tic()
        assertEquals(2, envois.size)
        image = jpeg(320, 240)
        t += DirectSitePc.PERIODE_SSTV_MS; d.tic()
        assertEquals(3, envois.size)
        // Well after LOS: nothing more.
        image = jpeg(300, 200)
        t += 400_000L; d.tic()
        assertTrue(envois.none { it.url.endsWith("sstv-image") && envois.indexOf(it) > 2 })
    }

    @Test fun `the favourites go only when chosen, on change, else once an hour`() {
        val (site, d) = direct()
        var fav = listOf("SO-50" to 27607, "ISS" to 25544)
        d.favoris = { fav }
        d.tic()
        assertTrue(envois.isEmpty())
        assertFalse(d.json().getJSONArray("canaux").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.first { it.getString("nom") == "favoris" }.getBoolean("on"))
        site.sert("/api/site/direct", "POST") { k -> if (k == "on_favoris") "1" else "" }
        d.tic()
        assertEquals(listOf("https://example.org/api/publication/favoris"), envois.map { it.url })
        assertEquals("favoris", envois[0].entetes["X-SatMe-Type"])
        val o = JSONObject(String(envois[0].corps!!))
        assertEquals("favoris", o.getString("type")); assertEquals(1, o.getInt("version")); assertEquals(t, o.getLong("t"))
        val sats = o.getJSONArray("satellites").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { x -> x.getString("nom") to x.getInt("norad") } } }
        assertEquals(listOf("ISS" to 25544, "SO-50" to 27607), sats)
        // Unchanged: not again before an hour.
        t += 600_000L; d.tic()
        assertEquals(1, envois.size)
        // Changed: at once.
        fav = listOf("ISS" to 25544)
        t += 1_000L; d.tic()
        assertEquals(2, envois.size)
        assertEquals(1, JSONObject(String(envois[1].corps!!)).getJSONArray("satellites").length())
        t += DirectSitePc.PERIODE_FAVORIS_MS - 1_000L; d.tic()
        assertEquals(2, envois.size)
        t += 1_000L; d.tic()
        assertEquals(3, envois.size)
        // Nothing while the target is not ready.
        fav = emptyList()
        reglages.edit().putBoolean(SitePc.CLE_PRET, false).apply()
        t += 1_000L; d.tic()
        assertEquals(3, envois.size)
    }

    @Test fun `a site that does not know the favourites is left alone for the day, without an error`() {
        val (site, d) = direct()
        d.favoris = { listOf("ISS" to 25544) }
        site.sert("/api/site/direct", "POST") { k -> if (k == "on_favoris") "1" else "" }
        reponse = { 404 to "{\"ok\":false,\"erreur\":\"type inconnu\"}" }
        d.tic()
        assertEquals(1, envois.size)
        val c = d.json().getJSONArray("canaux").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.first { it.getString("nom") == "favoris" }
        assertTrue(c.getBoolean("nonPris")); assertEquals("", c.getString("erreur"))
        repeat(5) { t += 3_600_000L; d.tic() }
        assertEquals(1, envois.size)
        reponse = { 200 to "{\"ok\":true}" }
        t += DirectSitePc.NON_PRIS_MS; d.tic()
        assertEquals(2, envois.size)
        assertFalse(d.json().toString().contains("\"nonPris\":true"))
    }

    @Test fun `to a folder the favourites are favoris_json`() {
        val st = StockageFichier(tmp.newFile("r-dossier.properties"))
        val dos = tmp.newFolder("site-dossier")
        val site = SitePc(st, tmp.newFolder(), { false }, { t }, SitePc.Transport { _, _, _, _ -> throw AssertionError("pas de web") })
        fr.f4ioz.satcombo.demo.ModulesStation.pose(st, emptySet())
        site.sert("/api/site/essai", "POST") { k -> when (k) { "cible" -> "dossier"; "dossier" -> dos.absolutePath; else -> "" } }
        assertTrue(site.configure)
        val d = DirectSitePc(st, site, { t })
        site.direct = d
        d.favoris = { listOf("ISS" to 25544) }
        site.sert("/api/site/direct", "POST") { k -> if (k == "on_favoris") "1" else "" }
        d.tic()
        val o = JSONObject(java.io.File(dos, "favoris.json").readText())
        assertEquals("favoris", o.getString("type"))
        assertEquals(25544, o.getJSONArray("satellites").getJSONObject(0).getInt("norad"))
    }

    // ------------------------------------------------------------------ « Description du montage »

    /** A ready-made plan of the catalogue as the site gets it (demonstration hardware). */
    private fun montage(id: String = "qo100-station") = CatalogueMateriel.PREREGLAGES.first { it.id == id }.chaine.let { c ->
        fr.f4ioz.satcombo.station.PublicationMontage.json(c.nom, c)
    }

    @Test fun `the station's hardware goes only when chosen, on change, else once an hour`() {
        val (site, d) = direct()
        var m = montage()
        d.montage = { m }
        d.tic()
        assertTrue(envois.isEmpty())
        val canal = { d.json().getJSONArray("canaux").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.first { it.getString("nom") == "montage" } }
        assertFalse(canal().getBoolean("on"))
        assertEquals("Description du montage", canal().getString("libelle"))
        site.sert("/api/site/direct", "POST") { k -> if (k == "on_montage") "1" else "" }
        d.tic()
        assertEquals(listOf("https://example.org/api/publication/montage"), envois.map { it.url })
        assertEquals("montage", envois[0].entetes["X-SatMe-Type"])
        val o = JSONObject(String(envois[0].corps!!))
        assertEquals("montage", o.getString("type")); assertEquals(1, o.getInt("version")); assertEquals(t, o.getLong("t"))
        assertEquals("Station QO-100", o.getString("plan"))
        assertTrue(o.getJSONArray("voies").length() >= 3)
        // Unchanged: not again before an hour (looked at once a minute).
        t += 600_000L; d.tic()
        assertEquals(1, envois.size)
        // Changed: within the minute.
        m = montage("qo100-pluto")
        t += 60_000L; d.tic()
        assertEquals(2, envois.size)
        t += DirectSitePc.PERIODE_MONTAGE_MS - 60_000L; d.tic()
        assertEquals(2, envois.size)
        t += 60_000L; d.tic()
        assertEquals(3, envois.size)
        // Nothing to show: nothing sent.
        m = null
        t += DirectSitePc.PERIODE_MONTAGE_MS; d.tic()
        assertEquals(3, envois.size)
    }

    @Test fun `a site that does not know the station's hardware is left alone for the day`() {
        val (site, d) = direct()
        d.montage = { montage() }
        site.sert("/api/site/direct", "POST") { k -> if (k == "on_montage") "1" else "" }
        reponse = { 404 to "{\"ok\":false,\"erreur\":\"type inconnu\"}" }
        d.tic()
        assertEquals(1, envois.size)
        repeat(5) { t += 3_600_000L; d.tic() }
        assertEquals(1, envois.size)
        reponse = { 200 to "{\"ok\":true}" }
        t += DirectSitePc.NON_PRIS_MS; d.tic()
        assertEquals(2, envois.size)
    }

    @Test fun `to a folder the station's hardware is montage_json`() {
        val st = StockageFichier(tmp.newFile("r-dossier-m.properties"))
        val dos = tmp.newFolder("site-dossier-m")
        val site = SitePc(st, tmp.newFolder(), { false }, { t }, SitePc.Transport { _, _, _, _ -> throw AssertionError("pas de web") })
        fr.f4ioz.satcombo.demo.ModulesStation.pose(st, emptySet())
        site.sert("/api/site/essai", "POST") { k -> when (k) { "cible" -> "dossier"; "dossier" -> dos.absolutePath; else -> "" } }
        val d = DirectSitePc(st, site, { t })
        site.direct = d
        d.montage = { montage() }
        site.sert("/api/site/direct", "POST") { k -> if (k == "on_montage") "1" else "" }
        d.tic()
        val o = JSONObject(java.io.File(dos, "montage.json").readText())
        assertEquals("montage", o.getString("type"))
        assertTrue(o.getJSONArray("elements").length() > 5)
    }

    // ------------------------------------------------------------------ « Station »

    /** A station with everything filled, and words the site must never get hidden where a careless copy would put them. */
    private fun stationPleine(az: Double = 123.456, el: Double = 30.04) = DirectSitePc.Station(
        sat = DirectSitePc.Sat("ISS\u0007", 25544, false, az, el, 812.7, -6.123, t + 60_000L, t + 600_000L, 61.27,
            listOf(DirectSitePc.Emetteur("Mode V/U FM", "FM", 437_800_000L, 145_990_000L), DirectSitePc.Emetteur("APRS", "AFSK", 145_825_000L, null)), "Mode V/U FM"),
        rotor = DirectSitePc.Rotor(true, 118.2, 28.9, az, el, true, false, false),
        parabole = null,
        sdr = DirectSitePc.Sdr(true, DirectSitePc.nomSdr(SdrPc.PLUTO), 437_800_000L),
        datv = DirectSitePc.DatvCourt(true, true, 7.43, 2.11, "F4XYZ", 10_494_750L),
        poste = DirectSitePc.Poste(DirectSitePc.nomPoste("IC9700"), true, 437_800_000L, 145_990_000L, "FM"),
        enEmission = false, mode = "phonie")

    /** Every key of [o], level by level, must be in [DirectSitePc.CLES_STATION]. */
    private fun verifieCles(o: JSONObject, niveau: String) {
        val permis = DirectSitePc.CLES_STATION.getValue(niveau)
        assertTrue(niveau + " : " + o.keySet(), permis.containsAll(o.keySet()))
        for (k in o.keySet()) when (val v = o.get(k)) {
            is JSONObject -> verifieCles(v, k)
            is org.json.JSONArray -> { assertEquals("emetteurs", k); for (i in 0 until v.length()) verifieCles(v.getJSONObject(i), "emetteur") }
            is String -> assertTrue(k, v.length <= 60 && v.none { it.isISOControl() })
            else -> assertTrue(k + " = " + v, v is Number || v is Boolean)
        }
    }

    @Test fun `the station's message holds only the listed keys, rounded and cut short`() {
        val o = DirectSitePc.stationJson(stationPleine())
        verifieCles(o, "")
        val s = o.getJSONObject("satellite")
        assertEquals(123.5, s.getDouble("az"), 1e-9); assertEquals(30.0, s.getDouble("el"), 1e-9)
        assertEquals("ISS", s.getString("nom")); assertEquals(813L, s.getLong("distanceKm")); assertEquals(-6.12, s.getDouble("vitesseKmS"), 1e-9)
        assertEquals("IC-9700", o.getJSONObject("reception").getJSONObject("poste").getString("modele"))
        assertEquals("ADALM-PLUTO", o.getJSONObject("reception").getJSONObject("sdr").getString("type"))
        assertTrue(o.getJSONObject("antennes").getJSONObject("rotor").getBoolean("enMouvement"))
        // A geostationary: no AOS/LOS, the fixed dish's pointing; a receiver off says so and nothing else.
        val geo = DirectSitePc.stationJson(DirectSitePc.Station(DirectSitePc.Sat("QO-100", 43700, true, 150.04, 29.71, aos = 5L, los = 9L),
            parabole = 150.04 to 29.71, sdr = DirectSitePc.Sdr(false, "RTL-SDR", 10_489_750_000L),
            datv = DirectSitePc.DatvCourt(false, true, 7.0, 1.0, "X", 10_494_750L), poste = DirectSitePc.Poste("IC-9700", false, 1L, 2L, "USB")))
        verifieCles(geo, "")
        assertFalse(geo.getJSONObject("satellite").has("aos"))
        assertEquals(29.7, geo.getJSONObject("antennes").getJSONObject("parabole").getDouble("el"), 1e-9)
        assertEquals(setOf("actif", "type"), geo.getJSONObject("reception").getJSONObject("sdr").keySet())
        assertEquals(setOf("actif", "verrouille"), geo.getJSONObject("reception").getJSONObject("datv").keySet())
        assertEquals(setOf("modele", "connecte"), geo.getJSONObject("reception").getJSONObject("poste").keySet())
        assertTrue(DirectSitePc.stationJson(DirectSitePc.Station()).toString().length < 40)
        assertTrue(o.toString().length < 1500)
    }

    @Test fun `the station goes only when chosen, on change at most every two seconds, else every 30 s`() {
        val (site, d) = direct()
        var st = stationPleine()
        d.station = { st }
        d.tic()
        assertTrue(envois.isEmpty())
        site.sert("/api/site/direct", "POST") { k -> if (k == "on_station") "1" else "" }
        d.tic()
        assertEquals(listOf("https://example.org/api/publication/direct-station"), envois.map { it.url })
        assertEquals("direct-station", envois[0].entetes["X-SatMe-Type"])
        val o = JSONObject(String(envois[0].corps!!))
        assertEquals(t, o.getLong("t")); assertEquals(1, o.getInt("version")); assertEquals("direct-station", o.getString("type"))
        assertFalse(o.toString().contains("le-jeton"))
        // Unchanged: nothing until the heartbeat.
        repeat(14) { t += 2_000L; d.tic() }
        assertEquals(1, envois.size)
        t += 2_000L; d.tic()
        assertEquals(2, envois.size)
        // Changed: within the period, not at once.
        st = stationPleine(az = 130.0)
        t += 1_000L; d.tic()
        assertEquals(2, envois.size)
        t += 1_000L; d.tic()
        assertEquals(3, envois.size)
        // Moving every second: still one every two seconds at most.
        repeat(10) { k -> st = stationPleine(az = 131.0 + k); t += 1_000L; d.tic() }
        assertEquals(8, envois.size)
        // Slower when chosen.
        site.sert("/api/site/direct", "POST") { k -> if (k == "periode_station") "10" else "" }
        val n = envois.size
        repeat(20) { k -> st = stationPleine(az = 200.0 + k); t += 1_000L; d.tic() }
        assertEquals(n + 2, envois.size)
    }

    @Test fun `a site that does not know the station is asked again ten minutes later, without an error`() {
        val (site, d) = direct()
        d.station = { stationPleine() }
        site.sert("/api/site/direct", "POST") { k -> if (k == "on_station") "1" else "" }
        reponse = { 404 to "{\"ok\":false,\"erreur\":\"type inconnu\"}" }
        d.tic()
        val c = d.json().getJSONArray("canaux").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.first { it.getString("nom") == "station" }
        assertTrue(c.getBoolean("nonPris")); assertEquals("", c.getString("erreur"))
        repeat(5) { t += 60_000L; d.tic() }
        assertEquals(1, envois.size)
        reponse = { 200 to "{\"ok\":true}" }
        t += DirectSitePc.NON_PRIS_STATION_MS; d.tic()
        assertEquals(2, envois.size)
    }

    @Test fun `to a folder the station is direct_station_json`() {
        val st = StockageFichier(tmp.newFile("r-dossier-st.properties"))
        val dos = tmp.newFolder("site-dossier-st")
        val site = SitePc(st, tmp.newFolder(), { false }, { t }, SitePc.Transport { _, _, _, _ -> throw AssertionError("pas de web") })
        fr.f4ioz.satcombo.demo.ModulesStation.pose(st, emptySet())
        site.sert("/api/site/essai", "POST") { k -> when (k) { "cible" -> "dossier"; "dossier" -> dos.absolutePath; else -> "" } }
        val d = DirectSitePc(st, site, { t })
        site.direct = d
        d.station = { stationPleine() }
        site.sert("/api/site/direct", "POST") { k -> if (k == "on_station") "1" else "" }
        d.tic()
        val o = JSONObject(java.io.File(dos, "direct/station.json").readText())
        assertEquals("ISS", o.getJSONObject("satellite").getString("nom"))
        assertEquals(t, o.getLong("t"))
    }
}
