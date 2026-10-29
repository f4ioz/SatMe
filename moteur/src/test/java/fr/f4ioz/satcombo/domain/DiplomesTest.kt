/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.LogEntry
import fr.f4ioz.satcombo.domain.Diplomes.Diplome
import fr.f4ioz.satcombo.domain.Diplomes.Genre
import fr.f4ioz.satcombo.domain.Diplomes.Image
import fr.f4ioz.satcombo.domain.Diplomes.Statut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class DiplomesTest {

    private val paris = TimeZone.getTimeZone("Europe/Paris")
    private val utc = TimeZone.getTimeZone("UTC")
    private fun date(s: String, tz: TimeZone = utc) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = tz }.parse(s)!!.time
    private fun jour(ms: Long, tz: TimeZone = utc) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = tz }.format(java.util.Date(ms))
    private fun res(n: String) = javaClass.getResource("/diplomes/$n")!!.readText()

    /** The text of Radio Club Querétaro's flyer (XE1RCQ, October 2026), as on the picture. */
    private val QUERETARO = """
        Amateur Radio
        on the International
        Space Station
        ARISS
        SSTV
        SERIES 34
        ARISS HARDWARE
        Images from the
        International Space Station (ISS)
        OCTOBER
        22 – 25, 2026
        SSTV transmissions from the ISS
        PARTICIPATION AWARD
        RadioClub Querétaro will grant a
        beautiful AWARD in recognition
        for receiving an image from
        ARISS SSTV Series 34.
        Only one award
        will be issued per participant.
        HOW TO PARTICIPATE
        1 Receive the SSTV images from the ISS (October 22–25, 2026).
        2 Save your best image.
        3 Fill out the form and upload your image to request your award.
        REQUEST DEADLINE:
        OCTOBER 30, 2026
        Scan the QR to access the form
        Radio Club Querétaro A.C.
        XE1RCQ
        50 Years
        https: //bit.ly/4dRLRke
    """.trimIndent()

    /** Radio Operadores del Este's certificate (KP3RE), as on the picture. */
    private val KP3RE = """
        HAM SATELITES PUERTO RICO
        CERTIFICADO ESPECIAL ARISS SSTV SERIE 34
        Hardware ARISS • Octubre 2026
        FRECUENCIA 437.550 MHz +// Desplazamiento Doppler
        MODO Robot 36 • 36 seg encendido / 2 min apagado
        FECHAS 22-25 Octubre • Inicio 22 Fin 25 Octubre 2026
        Otorgado a:
        HG1TTS Tompos Sándor
        Radio Operadores del Este, Inc. • KP3RE
    """.trimIndent()

    /** AMSAT-F relaying LABRE's certificate (30/01/2026), as on the post. */
    private val LABRE = """
        Emission de SSTV prévue du 06 février au 9 Février via le satellite UmKA-1 – Diplôme spécifique
        La LABRE, dans le cadre des célébrations de son 92e anniversaire, promouvra la transmission d'images en SSTV depuis le satellite UmKA-1 (RS40S). Les diffusions auront lieu du 6 au 9 février 2026 et une image spéciale liée au 92e anniversaire de la LABRE sera diffusée dans le monde entier. Ceux qui parviendront à la décoder auront droit à un certificat spécial délivré par l'organisation. L'événement est ouvert aux radioamateurs ainsi qu'aux auditeurs (SWL).
        Le satellite UmKA-1 (RS40S) transmettra sur la fréquence 437,625 MHz (attention à l'effet Doppler !) en mode Robot36.
        L'image reçue doit être d'au moins 50 % de qualité, c'est-à-dire qu'au moins la moitié de l'image doit être lisible.
        Date limite de soumission : jusqu'à 48 heures après la fin des diffusions.
        Lien : https://www.labre.org.br/labre-92-anos-transmissoes-em-sstv-valem-certificado-especial/
    """.trimIndent()

    // ------------------------------------------------------------------ announcements

    @Test fun `l'affiche de Querétaro donne le diplôme, sa période, sa date limite et son formulaire`() {
        val l = Diplomes.lisAnnonce(QUERETARO, date("2026-10-26 10:00"), paris)
        val d = l.diplome
        assertTrue(l.estDiplome)
        assertEquals(Genre.IMAGE, d.genre)
        assertEquals("ISS", d.satNom)
        assertEquals("2026-10-22 00:00", jour(d.debutMs, paris))
        assertEquals("2026-10-25 23:59", jour(d.finMs, paris))
        assertEquals("2026-10-30 23:59", jour(d.limiteMs, paris))
        assertEquals("https://bit.ly/4dRLRke", d.lienFormulaire)
        assertTrue(d.emetteur, d.emetteur.contains("Querétaro") && d.emetteur.contains("XE1RCQ"))
        assertTrue(d.nom, d.nom.contains("Participation Award") && d.nom.contains("ARISS SSTV Series 34"))
        assertTrue(d.unParParticipant)
        assertTrue(l.trouve.contains("date limite"))
    }

    @Test fun `la même affiche lue par tesseract (couleurs inversées, agrandie)`() {
        val d = Diplomes.lisAnnonce(res("ocr-queretaro.txt"), date("2026-10-27 08:00"), paris).diplome
        assertEquals("2026-10-22 00:00", jour(d.debutMs, paris))
        assertEquals("2026-10-25 23:59", jour(d.finMs, paris))
        assertEquals("2026-10-30 23:59", jour(d.limiteMs, paris))
        assertEquals("Participation Award — ARISS SSTV Series 34", d.nom)
        assertTrue(d.emetteur, d.emetteur.startsWith("RadioClub Querétaro") && d.emetteur.endsWith("XE1RCQ"))
        // The QR code's link is not read: the owner types it.
        assertEquals("", d.lienFormulaire)
    }

    @Test fun `des taches de lecture autour du mois et du jour`() {
        val d = Diplomes.lisAnnonce("PARTICIPATION AWARD\nft) OCTOBER\n\ni} 22 - 25, 2026\nREQUEST DEADLINE:\n33\") OCTOBER 30, 2026", date("2026-10-27 08:00"), paris).diplome
        assertEquals("2026-10-22 00:00", jour(d.debutMs, paris))
        assertEquals("2026-10-25 23:59", jour(d.finMs, paris))
        assertEquals("2026-10-30 23:59", jour(d.limiteMs, paris))
    }

    @Test fun `le certificat de KP3RE, en espagnol`() {
        val l = Diplomes.lisAnnonce(KP3RE, date("2026-10-27 08:00"), utc)
        val d = l.diplome
        assertTrue(l.estDiplome)
        assertEquals("2026-10-22", jour(d.debutMs).take(10))
        assertEquals("2026-10-25", jour(d.finMs).take(10))
        assertEquals(437_550_000L, d.freqHz)
        assertEquals("Robot 36", d.mode)
        assertTrue(d.emetteur, d.emetteur.startsWith("Radio Operadores del Este") && d.emetteur.endsWith("KP3RE"))
        assertFalse(d.emetteur.contains("HG1TTS"))
        assertEquals(0L, d.limiteMs)
    }

    @Test fun `la LABRE, un délai après la fin`() {
        val d = Diplomes.lisAnnonce(LABRE, date("2026-01-30 12:00"), utc, listOf("UmKA 1 (RS40S)" to 57172), "AMSAT-F").diplome
        assertEquals("2026-02-06", jour(d.debutMs).take(10))
        assertEquals("2026-02-09 23:59", jour(d.finMs))
        assertEquals(d.finMs + 48 * 3_600_000L, d.limiteMs)
        assertEquals(437_625_000L, d.freqHz)
        assertTrue(d.emetteur, d.emetteur.startsWith("LABRE"))
        assertTrue(d.conditions.contains("50 %"))
        assertTrue(d.notes.contains("SWL"))
        assertEquals("UmKA 1 (RS40S)", d.satNom)
        assertEquals(57172, d.catnum)
    }

    @Test fun `un texte sans diplôme le dit`() {
        val l = Diplomes.lisAnnonce("UmKA-1 SSTV du 27 au 29 octobre 2026, 437,625 MHz", date("2026-10-27 08:00"))
        assertFalse(l.estDiplome)
    }

    // ------------------------------------------------------------------ online sources

    @Test fun `la page du diplôme ARISS donne chaque session récente`() {
        val l = Diplomes.lisPzk(res("pzk-extrait.html"), date("2026-10-27 08:00"))
        assertEquals(listOf("pzk:75-2", "pzk:75"), l.map { it.id })
        val d = l.first()
        assertTrue(d.nom, d.nom.contains("ARISS Hardware") && d.nom.contains("Series 34"))
        assertEquals("2026-10-22 00:00", jour(d.debutMs))
        assertEquals("2026-10-25 23:59", jour(d.finMs))
        assertEquals(437_550_000L, d.freqHz)
        assertEquals(Diplomes.GALERIE_ARISS, d.lienFormulaire)
        assertFalse(l[1].nom.contains("Series 34"))
    }

    @Test fun `le bulletin AMSAT, le certificat des séries, pas les palmarès ni les brèves`() {
        val l = Diplomes.lisRss(res("amsat-ans-extrait.rss"), "AMSAT News Service", "https://www.amsat.org/", date("2026-10-27 08:00"))
        // ANS-270 announces Series 33 (Oct 2-6), ANS-291 Series 34 (Oct 22-25), each with its certificate; ANS-298 gives no dates;
        // « Earns … Award #21 » and the « Satellite Shorts » are no offer.
        assertEquals(listOf("2026-10-02", "2026-10-22"), l.map { jour(it.debutMs).take(10) }.sorted())
        assertTrue(l.all { Diplomes.estAriss(it) && it.id.startsWith("rss:") && it.nom.startsWith("ARISS SSTV Award") })
        assertTrue(l.any { it.nom == "ARISS SSTV Award — Series 34" })
        // Known from its page too: kept once.
        val pzk = Diplomes.lisPzk(res("pzk-extrait.html"), date("2026-10-27 08:00"))
        assertEquals(pzk.map { it.id }, Diplomes.fusionne(l + pzk).map { it.id })
    }

    @Test fun `AMSAT-UK relaie la galerie ARISS, pas un diplôme de RS0ISS`() {
        val l = Diplomes.lisRss(res("amsat-uk-extrait.rss"), "AMSAT-UK", "https://amsat-uk.org/", date("2026-10-27 08:00"))
        assertEquals(1, l.size)
        val d = l.first()
        assertEquals("ARISS", d.emetteur)
        assertEquals("ARISS SSTV Award — Series 34", d.nom)
        assertEquals("2026-10-22 15:10", jour(d.debutMs))
        assertEquals("2026-10-26 17:45", jour(d.finMs))
        assertEquals(1, Diplomes.fusionne(l + Diplomes.lisPzk(res("pzk-extrait.html"), date("2026-10-27 08:00")).take(1)).size)
    }

    @Test fun `un diplôme de club reste à côté de celui de l'ARISS`() {
        val pzk = Diplomes.lisPzk(res("pzk-extrait.html"), date("2026-10-27 08:00"))
        val club = Diplomes.lisAnnonce(QUERETARO, date("2026-10-26 10:00"), paris).diplome
        assertEquals(3, Diplomes.fusionne(pzk + club).size)
    }

    // ------------------------------------------------------------------ matching

    private val serie34 = Diplome("pzk:75-2", "ARISS SSTV Award", "ARISS (diplôme géré par le PZK)", Genre.IMAGE, satNom = "ISS",
        debutMs = date("2026-10-22 00:00"), finMs = date("2026-10-25 23:59"))

    private val images = listOf(
        Image("a.png", "ISS", date("2026-10-23 08:43"), complete = true, note = 71),
        Image("b.png", "ISS (ZARYA)", date("2026-10-24 09:10"), complete = false, note = 88),
        Image("c.png", "UmKA 1 (RS40S)", date("2026-10-24 12:00"), note = 95),
        Image("d.png", "ISS", date("2026-10-26 17:00"), note = 99),
        Image("e.png", "ISS", date("2026-10-26 01:00"), note = 40),
        Image("f.png", "ISS", date("2026-10-23 10:00"), note = 90, traitee = true),
    )

    @Test fun `les images de la fenêtre, du bon satellite, les meilleures d'abord`() {
        assertEquals(listOf("b.png", "a.png", "e.png"), Diplomes.imagesDe(serie34, images).map { it.nom })
    }

    @Test fun `la fenêtre s'étend à l'agenda ARISS qui la chevauche`() {
        val d = Diplomes.etend(serie34, listOf(date("2026-10-22 15:30")..date("2026-10-26 17:45"), date("2026-11-20 00:00")..date("2026-11-22 00:00")))
        assertEquals(date("2026-10-22 00:00"), d.debutMs)
        assertEquals(date("2026-10-26 17:45"), d.finMs)
        assertTrue(Diplomes.imagesDe(d, images).any { it.nom == "d.png" })
    }

    @Test fun `les statuts d'un événement`() {
        val club = serie34.copy(id = "affiche:x", limiteMs = date("2026-10-30 23:59"))
        val b = { t: String, imgs: List<Image> -> Diplomes.bilan(club, imgs, emptyList(), "", null, date(t), utc) }
        assertEquals(Statut.A_VENIR, b("2026-10-20 10:00", images).statut)
        assertEquals(Statut.PENDANT, b("2026-10-23 01:00", emptyList()).statut)
        val ob = b("2026-10-27 10:00", images)
        assertEquals(Statut.OBTENABLE, ob.statut)
        assertEquals("Obtenable — à demander avant le 30/10", ob.libelle)
        assertEquals(Statut.EXPIRE, b("2026-10-31 10:00", images).statut)
        assertEquals(Statut.MANQUE, Diplomes.bilan(serie34, emptyList(), emptyList(), "", null, date("2026-10-27 10:00"), utc).statut)
        val demande = Diplomes.bilan(club, images, emptyList(), "", Diplomes.Suivi(Diplomes.EtatSuivi.DEMANDE, date("2026-10-27 09:00")), date("2026-11-02 10:00"), utc)
        assertEquals(Statut.DEMANDE, demande.statut)
        assertEquals("Demandé le 27/10", demande.libelle)
    }

    // ------------------------------------------------------------------ the log

    private fun qso(call: String, loc: String = "", sat: String = "SO-50", catnum: Int = 27607, qth: String = "", mine: String = "JN18") =
        LogEntry(timeMs = 1L, satName = sat, catnum = catnum, azimuthDeg = 0.0, elevationDeg = 0.0, myLocator = mine, callsign = call, theirLocator = loc, qth = qth)

    @Test fun `les locators, entités, États et stations comptés dans le carnet`() {
        val log = listOf(qso("EA4ABC", "IN80do"), qso("EA4ABC", "in80"), qso("DL1XYZ", "JO62"), qso("W5XX", "EM12", qth = "Dallas, TX"),
            qso("KL7AA", "BP51"), qso("K1ABC", "FN42", qth = "Boston"), qso("VE3ABC", "FN03"), qso("VA3XYZ", "FN04"), qso("F4XYZ", "JN19", sat = "QO-100", catnum = 43700),
            qso("ON4ZZ", "", mine = "JN19"), qso("F5AAA", "JN18", sat = "", catnum = 0))
        assertEquals(listOf("BP51", "EM12", "FN03", "FN04", "FN42", "IN80", "JN19", "JO62"), Diplomes.grilles(log))
        assertEquals(listOf("JN19"), Diplomes.mesGrilles(log, "JN18fs"))
        assertTrue(Diplomes.dxcc(log).containsAll(listOf("Espagne", "Allemagne", "États-Unis", "Alaska", "Canada", "France", "Belgique")))
        assertEquals(listOf("AK", "TX"), Diplomes.etats(log))
        assertEquals(1, Diplomes.usSansEtat(log))
        assertTrue(Diplomes.oscar(log).containsAll(listOf("US-TX", "US-AK", "VE3", "Espagne")))
        assertFalse(Diplomes.stations(log).contains("F4XYZ"))
        val vucc = Diplomes.CATALOGUE.first { it.id == "cat:vucc" }
        val b = Diplomes.bilan(vucc, emptyList(), log, "JN18fs", null, 0L)
        assertEquals(Statut.EN_COURS, b.statut)
        assertEquals("En cours : 8/100 locators", b.libelle)
        val scc = Diplomes.bilan(Diplomes.CATALOGUE.first { it.id == "cat:amsat-scc" }, emptyList(), log, "JN18fs", null, 0L)
        assertEquals(Statut.OBTENABLE, scc.statut)
    }

    // ------------------------------------------------------------------ notices

    @Test fun `obtenable puis échéance, chacun une fois`() {
        val club = serie34.copy(id = "affiche:x", nom = "Participation Award", limiteMs = date("2026-10-30 23:59"))
        val t = date("2026-10-28 10:00")
        val bilans = listOf(Diplomes.bilan(club, images, emptyList(), "", null, t, utc))
        val a = Diplomes.aSignaler(bilans, emptySet(), t, 3, tz = utc)
        assertEquals(listOf("obtenable:affiche:x", "echeance:affiche:x"), a.map { it.cle })
        assertEquals("Plus que 3 jours pour demander : Participation Award (avant le 30/10)", a[1].texte)
        assertTrue(Diplomes.aSignaler(bilans, a.map { it.cle }.toSet(), t, 3).isEmpty())
        // Too early for the deadline's notice.
        assertEquals(listOf("obtenable:affiche:x"), Diplomes.aSignaler(bilans, emptySet(), date("2026-10-26 10:00"), 3).map { it.cle })
        // A new award announced online, before its event.
        val avant = listOf(Diplomes.bilan(club, emptyList(), emptyList(), "", null, date("2026-10-20 10:00"), utc))
        assertEquals(listOf("nouveau:affiche:x"), Diplomes.aSignaler(avant, emptySet(), date("2026-10-20 10:00"), 3).map { it.cle })
    }

    @Test fun `le fichier se relit tel quel, et un fichier abîmé ne casse rien`() {
        val club = Diplomes.lisAnnonce(QUERETARO, date("2026-10-26 10:00"), paris).diplome
        val f = Diplomes.Fichier(Diplomes.Reglages(jours = 5, nouveau = false), Diplomes.Identite("Olivier", "f4ioz@example.org"), listOf(club),
            mapOf(club.id to Diplomes.Suivi(Diplomes.EtatSuivi.RECU, 42L, "x.png", "merci")), setOf("obtenable:" + club.id))
        assertEquals(f, Diplomes.lisFichier(Diplomes.ecrit(f).toString()))
        assertEquals(Diplomes.Fichier(), Diplomes.lisFichier("{pas du json"))
    }
}
