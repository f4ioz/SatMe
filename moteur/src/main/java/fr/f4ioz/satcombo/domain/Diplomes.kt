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
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * « Agenda › Diplômes »: the awards a satellite station can claim, and how
 * far it is from each — pure logic, both platforms.
 *
 * Two kinds. An **event** award ([Genre.IMAGE]): pictures received during a
 * window (an ARISS SSTV session, a club's certificate for it), asked for
 * before a deadline. A **log** award: grids, DXCC entities, states, contacts
 * worked by satellite, counted from the log (worked, not confirmed: the
 * issuer checks the confirmations).
 *
 * Where they come from: [CATALOGUE] (the standing awards, their rules read on
 * their issuers' pages — sources beside each), the ARISS SSTV award's own
 * page ([lisPzk]), the news feeds ([lisRss]) and a flyer or a pasted
 * announcement ([lisAnnonce]). Nothing here asks for anything: the owner
 * fills the issuer's form himself.
 */
object Diplomes {

    enum class Genre(val unite: String) {
        /** Pictures received during the event's window. */
        IMAGE("image"),
        /** Distinct 4-character grids worked by satellite (VUCC). */
        GRILLES("locators"),
        /** Distinct grids the station worked *from*, home excluded (VUCC/r). */
        MES_GRILLES("locators activés"),
        /** DXCC entities worked by satellite. */
        DXCC("entités"),
        /** US states worked by satellite (WAS). */
        ETATS("États"),
        /** AMSAT's « Oscar » awards: US states, DXCC entities and Canadian call areas, each once. */
        OSCAR("États / entités / zones"),
        /** Contacts by satellite. */
        CONTACTS("contacts"),
        /** Different stations worked on low-orbit satellites. */
        STATIONS("stations"),
        /** Rules SatMe cannot count: said, checked by the owner. */
        LIBRE(""),
    }

    enum class Statut(val libelle: String) {
        A_VENIR("à venir"), PENDANT("événement en cours"), OBTENABLE("obtenable"), EN_COURS("en cours"),
        DEMANDE("demandé"), RECU("reçu"), EXPIRE("expiré"), MANQUE("manqué"), A_VERIFIER("à vérifier"), MASQUE("masqué"),
    }

    data class Diplome(
        /** Stable: « pzk:75-2 », « cat:vucc », « affiche:… », « rss:<link> ». */
        val id: String,
        val nom: String,
        /** Who issues it: « ARISS (géré par le PZK) », « Radio Club Querétaro XE1RCQ ». */
        val emetteur: String,
        val genre: Genre,
        /** How many [Genre.unite] it takes. */
        val objectif: Int = 1,
        /** The satellite its pictures come from ("" any), its NORAD number (0 unknown). */
        val satNom: String = "",
        val catnum: Int = 0,
        /** The event's window (0: a standing award). */
        val debutMs: Long = 0L,
        val finMs: Long = 0L,
        /** Last moment to ask (0: none said). */
        val limiteMs: Long = 0L,
        /** The rules, as the issuer says them (short). */
        val conditions: String = "",
        val lienReglement: String = "",
        val lienFormulaire: String = "",
        /** An address to write to when there is no form. */
        val courrielDemande: String = "",
        val frais: String = "",
        /** What the form asks: « indicatif », « nom », « courriel », « locator », « image », « date », « heure », « frequence », « mode », « materiel ». */
        val champs: List<String> = emptyList(),
        /** Where it was found: « Catalogue SatMe », « ariss.pzk.org.pl », « AMSAT-F », « Affiche », « Démonstration ». */
        val source: String = "",
        val sourceLien: String = "",
        /** When its rules were last read (« 27/10/2026 »), for the standing ones. */
        val verifie: String = "",
        val freqHz: Long = 0L,
        val mode: String = "",
        /** Only one per participant (the flyer says so). */
        val unParParticipant: Boolean = false,
        val notes: String = "",
        val fictif: Boolean = false,
    ) {
        val evenement: Boolean get() = genre == Genre.IMAGE
    }

    /** A picture of the gallery as the matching needs it. */
    data class Image(val nom: String, val satNom: String, val quandMs: Long, val complete: Boolean = true,
                     /** Its mark (the gallery's sorting), -1 unknown. */
                     val note: Int = -1, val mode: String = "",
                     /** Corrected or fused: never what an issuer asks for. */
                     val traitee: Boolean = false)

    enum class EtatSuivi { AUCUN, DEMANDE, RECU, MASQUE }

    /** What the owner said of an award: asked (when), received (when, its picture), hidden. */
    data class Suivi(val etat: EtatSuivi = EtatSuivi.AUCUN, val quandMs: Long = 0L, val certificat: String = "", val note: String = "")

    data class Bilan(
        val diplome: Diplome,
        val statut: Statut,
        val compte: Int,
        /** The pictures that qualify (best first), for an event award. */
        val images: List<Image> = emptyList(),
        /** What was counted (grids, entities…), sorted. */
        val elements: List<String> = emptyList(),
        /** Said to the owner: « Obtenable — à demander avant le 30/10 », « En cours : 37/100 locators ». */
        val libelle: String = "",
        /** A remark on the count (« 3 contacts US sans État noté »). */
        val remarque: String = "",
        val suivi: Suivi = Suivi(),
    )

    const val JOUR = 86_400_000L
    /** Pictures this close to the window still count (clocks, a session started early). */
    const val MARGE_MS = 2 * 3_600_000L

    // ------------------------------------------------------------------ the standing awards

    /**
     * The standing awards SatMe can count, their rules as read on the
     * issuers' pages on 27/10/2026:
     * - AMSAT awards: https://www.amsat.org/awards/ (eQSL and e-mail
     *   confirmations not accepted, LoTW screenshots are; applications by
     *   e-mail to the Director of Contests and Awards, KK5DO).
     * - AMSAT VUCC/r: https://www.amsat.org/reverse-vucc-or-vucc-r-award/
     * - ARRL VUCC: https://www.arrl.org/vucc and its rules
     *   https://www.arrl.org/files/file/Awards/VUCC_Rules_March_2023.pdf
     *   (Satellite: 100 grids, endorsements every 25, QSOs from 1983-01-01,
     *   all made within 200 km).
     * - ARRL DXCC Satellite: https://www.arrl.org/dxcc-rules (100 entities
     *   since 1965-03-01, the confirmation showing a satellite QSO).
     * - ARRL WAS Satellite: https://www.arrl.org/was (50 states, any
     *   satellite, LoTW accepted).
     * Searched and not found: a standing satellite award from AMSAT-F or the
     * REF (https://web.r-e-f.org/les-diplomes/ lists the DDFM family only),
     * none issued by AMSAT-UK (https://amsat-uk.org/satellites/operating-awards/
     * links to the others). AMSAT-F relays one-off certificates (LABRE's
     * UmKA-1 SSTV, Feb. 2026): those come through its feed.
     */
    val CATALOGUE: List<Diplome> = listOf(
        Diplome("cat:amsat-scc", "AMSAT Satellite Communicators Club", "AMSAT", Genre.CONTACTS, 1,
            conditions = "Le premier contact par satellite. Pas de QSL : un compte rendu du contact suffit.",
            lienReglement = "https://www.amsat.org/awards/", courrielDemande = "kk5do@amsat.org",
            frais = "1 \$ membre AMSAT, 2 \$ sinon (payé sur la boutique AMSAT après accord)",
            champs = listOf("indicatif", "nom", "courriel", "contact"), source = "Catalogue SatMe", verifie = "27/10/2026"),
        Diplome("cat:amsat-oscar-20", "AMSAT Oscar Satellite Communications Achievement", "AMSAT", Genre.OSCAR, 20,
            conditions = "20 contacts par satellite, chacun avec un État américain, DC, une entité DXCC ou une zone d'appel canadienne différente ; tous faits à moins de 40 km les uns des autres.",
            lienReglement = "https://www.amsat.org/awards/", courrielDemande = "kk5do@amsat.org", frais = "5 \$ membre AMSAT, 10 \$ sinon, plus le port",
            champs = listOf("indicatif", "nom", "courriel", "liste"), source = "Catalogue SatMe", verifie = "27/10/2026"),
        Diplome("cat:amsat-oscar-60", "AMSAT Oscar Sexagesimal", "AMSAT", Genre.OSCAR, 60,
            conditions = "Comme l'Oscar Achievement, avec 60 États / entités / zones différents (cumulatif).",
            lienReglement = "https://www.amsat.org/awards/", courrielDemande = "kk5do@amsat.org", frais = "5 \$ membre AMSAT, 10 \$ sinon, plus le port",
            champs = listOf("indicatif", "nom", "courriel", "liste"), source = "Catalogue SatMe", verifie = "27/10/2026"),
        Diplome("cat:amsat-oscar-100", "AMSAT Oscar Century", "AMSAT", Genre.OSCAR, 100,
            conditions = "Comme l'Oscar Achievement, avec 100 États / entités / zones différents (cumulatif).",
            lienReglement = "https://www.amsat.org/awards/", courrielDemande = "kk5do@amsat.org", frais = "5 \$ membre AMSAT, 10 \$ sinon, plus le port",
            champs = listOf("indicatif", "nom", "courriel", "liste"), source = "Catalogue SatMe", verifie = "27/10/2026"),
        Diplome("cat:amsat-w4ami", "AMSAT W4AMI Satellite Operator Achievement", "AMSAT", Genre.CONTACTS, 1000,
            conditions = "1 000 contacts par satellite (OSCAR-6 et suivants) ; mentions tous les 1 000 de plus, certificat spécial à 5 000.",
            lienReglement = "https://www.amsat.org/awards/", courrielDemande = "kk5do@amsat.org", frais = "5 \$ membre AMSAT, 10 \$ sinon, plus le port",
            champs = listOf("indicatif", "nom", "courriel", "liste"), source = "Catalogue SatMe", verifie = "27/10/2026"),
        Diplome("cat:samsat", "SA AMSAT Satellite Communication Achievement", "AMSAT (Afrique du Sud)", Genre.STATIONS, 25,
            conditions = "25 stations différentes contactées par des satellites en orbite basse (AO-10, AO-13, AO-40 exclus ; QO-100, géostationnaire, non compté ici).",
            lienReglement = "https://www.amsat.org/awards/", courrielDemande = "kk5do@amsat.org", frais = "5 \$ membre AMSAT, 10 \$ sinon, plus le port",
            champs = listOf("indicatif", "nom", "courriel", "liste"), source = "Catalogue SatMe", verifie = "27/10/2026"),
        Diplome("cat:vucc-r", "AMSAT VUCC/r (Reverse VUCC, rover)", "AMSAT", Genre.MES_GRILLES, 100,
            conditions = "Des contacts confirmés faits depuis 100 locators différents (hors locator de la station), contacts depuis le 1/1/1983, règles VUCC de l'ARRL.",
            lienReglement = "https://www.amsat.org/reverse-vucc-or-vucc-r-award/", courrielDemande = "kk5do@amsat.org", frais = "5 \$ membre AMSAT, 10 \$ sinon, plus le port",
            champs = listOf("indicatif", "nom", "courriel", "liste"), source = "Catalogue SatMe", verifie = "27/10/2026"),
        Diplome("cat:vucc", "VUCC Satellite", "ARRL", Genre.GRILLES, 100,
            conditions = "100 locators confirmés par satellite (mentions tous les 25) ; contacts depuis le 1/1/1983, tous faits à moins de 200 km les uns des autres. Adhésion ARRL exigée pour les stations américaines seulement.",
            lienReglement = "https://www.arrl.org/vucc", lienFormulaire = "https://lotw.arrl.org/",
            frais = "LoTW : 15 \$ (international) + 0,15 \$ par QSO, plus le certificat (30 \$ hors USA)",
            champs = listOf("indicatif", "nom", "courriel", "liste"), source = "Catalogue SatMe", verifie = "27/10/2026",
            notes = "Règlement complet : https://www.arrl.org/files/file/Awards/VUCC_Rules_March_2023.pdf"),
        Diplome("cat:dxcc-sat", "DXCC Satellite", "ARRL", Genre.DXCC, 100,
            conditions = "100 entités DXCC contactées par satellite depuis le 1/3/1965, la confirmation disant que c'était par satellite ; mentions à 125, 150, 175, 200…",
            lienReglement = "https://www.arrl.org/dxcc-rules", lienFormulaire = "https://lotw.arrl.org/",
            frais = "selon le barème ARRL (LoTW ou cartes)", champs = listOf("indicatif", "nom", "courriel", "liste"),
            source = "Catalogue SatMe", verifie = "27/10/2026"),
        Diplome("cat:was-sat", "WAS Satellite (Worked All States)", "ARRL", Genre.ETATS, 50,
            conditions = "Les 50 États américains contactés par satellite (n'importe lequel, n'importe quel mode), stations à terre. LoTW accepté.",
            lienReglement = "https://www.arrl.org/was", lienFormulaire = "https://lotw.arrl.org/",
            frais = "LoTW : 15 \$ hors membres américains, plus le certificat (12 à 17 \$)", champs = listOf("indicatif", "nom", "courriel", "liste"),
            source = "Catalogue SatMe", verifie = "27/10/2026"),
    )

    /** The ARISS SSTV award's rules (https://ariss.pzk.org.pl/sstv/, read 27/10/2026). */
    const val PZK = "https://ariss.pzk.org.pl/sstv/"
    const val GALERIE_ARISS = "https://ariss-usa.org/ARISS_SSTV/submit.php"
    const val CONDITIONS_ARISS = "Recevoir et décoder au moins une image pendant la session (partielle acceptée si on la reconnaît). " +
        "La déposer dans la galerie ARISS SSTV avec un courriel valide, le nom et l'indicatif voulus sur le diplôme, puis « I agree ». " +
        "Diplôme PNG envoyé par courriel au plus tard trois semaines après la fin. Pas de date limite écrite : déposer vite, la galerie de la session ferme ensuite."

    // ------------------------------------------------------------------ counting the log

    private val GRILLE = Regex("^[A-R]{2}[0-9]{2}")

    /** A contact made through a satellite (every contact of the log is, but a test row may lack it). */
    fun parSatellite(c: LogEntry) = c.catnum > 0 || c.satName.isNotBlank()

    /** The distinct 4-character grids worked by satellite. */
    fun grilles(log: List<LogEntry>): List<String> = log.filter(::parSatellite)
        .mapNotNull { GRILLE.find(it.theirLocator.trim().uppercase())?.value }.distinct().sorted()

    /** The distinct grids the station worked from, its home [maison] excluded (VUCC/r). */
    fun mesGrilles(log: List<LogEntry>, maison: String): List<String> {
        val m = GRILLE.find(maison.trim().uppercase())?.value
        return log.filter(::parSatellite).mapNotNull { GRILLE.find(it.myLocator.trim().uppercase())?.value }.distinct().filter { it != m }.sorted()
    }

    fun dxcc(log: List<LogEntry>): List<String> = log.filter(::parSatellite).mapNotNull { Dxcc.entite(it.callsign)?.nom }.distinct().sorted()

    /** The 50 states' codes. */
    val ETATS_US = ("AL AK AZ AR CA CO CT DE FL GA HI ID IL IN IA KS KY LA ME MD MA MI MN MS MO MT NE NV NH NJ NM NY NC ND OH OK OR PA RI SC SD TN TX UT VT VA WA WV WI WY").split(' ').toSet()

    private val ETAT_FIN = Regex("(?:,|\\s)\\s*([A-Z]{2})\\.?\\s*(?:\\d{5}(?:-\\d{4})?)?\\s*$")

    /**
     * A US contact's state: Alaska and Hawaii by their prefix, else the town
     * noted (« Denver, CO »), else the note (« WAS: CO »). Null for a US
     * contact whose state is not said; the non-US are not asked.
     */
    fun etat(c: LogEntry): String? {
        val e = Dxcc.entite(c.callsign) ?: return null
        return when (e.nom) {
            "Alaska" -> "AK"
            "Hawaï" -> "HI"
            "États-Unis" -> ETAT_FIN.find(c.qth.trim().uppercase())?.groupValues?.get(1)?.takeIf { it in ETATS_US }
                ?: Regex("\\b(?:WAS|STATE|ÉTAT|ETAT)\\s*[:=]?\\s*([A-Z]{2})\\b", RegexOption.IGNORE_CASE).find(c.note)?.groupValues?.get(1)?.uppercase()?.takeIf { it in ETATS_US }
            else -> null
        }
    }

    private fun estUs(c: LogEntry) = Dxcc.entite(c.callsign)?.nom in setOf("États-Unis", "Alaska", "Hawaï")

    fun etats(log: List<LogEntry>): List<String> = log.filter(::parSatellite).mapNotNull(::etat).distinct().sorted()

    /** The US contacts whose state is not known. */
    fun usSansEtat(log: List<LogEntry>): Int = log.filter(::parSatellite).count { estUs(it) && etat(it) == null }

    /**
     * AMSAT's « Oscar » count: a US state (when known; DC too), a Canadian
     * call area (VE1… VA3…: its digit), any other DXCC entity — each once.
     */
    fun oscar(log: List<LogEntry>): List<String> = log.filter(::parSatellite).mapNotNull { c ->
        val e = Dxcc.entite(c.callsign) ?: return@mapNotNull null
        when {
            estUs(c) -> etat(c)?.let { "US-$it" }
            e.nom == "Canada" -> IndicatifCompose.decoupe(c.callsign.uppercase()).lieu.firstOrNull { it.isDigit() }?.let { "VE$it" }
            else -> e.nom
        }
    }.distinct().sorted()

    /** Low-orbit satellites only: the geostationary QO-100 and the high AO-10, AO-13, AO-40 are not. */
    private val HAUTS = setOf(43700, 14129, 19216, 26609)
    private fun haut(c: LogEntry) = c.catnum in HAUTS || Regex("QO-?100|AO-?(10|13|40)\\b|ES'HAIL", RegexOption.IGNORE_CASE).containsMatchIn(c.satName)

    fun stations(log: List<LogEntry>): List<String> = log.filter { parSatellite(it) && !haut(it) && it.callsign.isNotBlank() }
        .map { IndicatifCompose.base(it.callsign.uppercase()) }.filter { it.isNotBlank() }.distinct().sorted()

    // ------------------------------------------------------------------ the pictures of an event

    private fun norm(s: String) = s.uppercase().replace(Regex("\\(.*?\\)"), "").replace(Regex("[^A-Z0-9]"), "")

    /** [image] comes from [d]'s satellite (any when it names none). */
    fun memeSatellite(d: Diplome, satImage: String): Boolean {
        val a = norm(d.satNom); val b = norm(satImage)
        if (a.isEmpty()) return true
        if (b.isEmpty()) return false
        return a == b || (a.length >= 3 && b.startsWith(a)) || (b.length >= 3 && a.startsWith(b))
    }

    /** The pictures received during [d]'s window (a margin around it), the best first: marked higher, complete, then the earliest. */
    fun imagesDe(d: Diplome, images: List<Image>): List<Image> {
        if (!d.evenement || d.debutMs <= 0) return emptyList()
        return images.filter { !it.traitee && it.quandMs in (d.debutMs - MARGE_MS)..(d.finMs + MARGE_MS) && memeSatellite(d, it.satNom) }
            .sortedWith(compareByDescending<Image> { it.note }.thenByDescending { it.complete }.thenBy { it.quandMs })
    }

    /**
     * An event award's window widened by the periods that announce the same
     * session ([periodes]: the ARISS calendar, the campaign) when they overlap
     * it — the award's page gives whole days, the calendar the hours, and a
     * session is sometimes lengthened.
     */
    fun etend(d: Diplome, periodes: List<LongRange>): Diplome {
        if (!d.evenement || d.debutMs <= 0) return d
        var de = d.debutMs; var a = d.finMs
        // Overlapping it, and by two days at most each side: another series on the same satellite stays out.
        for (p in periodes) if (p.first <= d.finMs && p.last >= d.debutMs) { de = minOf(de, maxOf(p.first, d.debutMs - 2 * JOUR)); a = maxOf(a, minOf(p.last, d.finMs + 2 * JOUR)) }
        return if (de == d.debutMs && a == d.finMs) d else d.copy(debutMs = de, finMs = a)
    }

    // ------------------------------------------------------------------ the status

    private fun jour(ms: Long, tz: TimeZone) = SimpleDateFormat("dd/MM", Locale.FRANCE).apply { timeZone = tz }.format(java.util.Date(ms))

    /** [d]'s state at [t] for this station: its pictures, the log ([monCarre] its home grid), what the owner said. */
    fun bilan(d: Diplome, images: List<Image>, log: List<LogEntry>, monCarre: String, suivi: Suivi?, t: Long,
              tz: TimeZone = TimeZone.getDefault()): Bilan {
        val s = suivi ?: Suivi()
        var remarque = ""
        val imgs = imagesDe(d, images)
        val elements: List<String> = when (d.genre) {
            Genre.IMAGE -> emptyList()
            Genre.GRILLES -> grilles(log)
            Genre.MES_GRILLES -> mesGrilles(log, monCarre)
            Genre.DXCC -> dxcc(log)
            Genre.ETATS -> etats(log).also { val n = usSansEtat(log); if (n > 0) remarque = "$n contact${if (n > 1) "s" else ""} US sans État noté (à voir sur LoTW)" }
            Genre.OSCAR -> oscar(log).also { val n = usSansEtat(log); if (n > 0) remarque = "$n contact${if (n > 1) "s" else ""} US sans État noté" }
            Genre.CONTACTS -> emptyList()
            Genre.STATIONS -> stations(log)
            Genre.LIBRE -> emptyList()
        }
        val compte = when (d.genre) {
            Genre.IMAGE -> imgs.size
            Genre.CONTACTS -> log.count(::parSatellite)
            Genre.LIBRE -> 0
            else -> elements.size
        }
        val statut = statut(d, compte, s, t)
        return Bilan(d, statut, compte, imgs, elements, libelle(d, statut, compte, s, t, tz), remarque, s)
    }

    fun statut(d: Diplome, compte: Int, s: Suivi, t: Long): Statut {
        when (s.etat) {
            EtatSuivi.RECU -> return Statut.RECU
            EtatSuivi.DEMANDE -> return Statut.DEMANDE
            EtatSuivi.MASQUE -> return Statut.MASQUE
            EtatSuivi.AUCUN -> {}
        }
        val passe = d.limiteMs > 0 && t > d.limiteMs
        if (d.evenement) {
            if (d.debutMs > 0 && t < d.debutMs - MARGE_MS) return if (passe) Statut.EXPIRE else Statut.A_VENIR
            if (compte >= d.objectif) return if (passe) Statut.EXPIRE else Statut.OBTENABLE
            if (d.finMs > 0 && t <= d.finMs + MARGE_MS && !passe) return Statut.PENDANT
            return if (passe) Statut.EXPIRE else Statut.MANQUE
        }
        if (passe) return Statut.EXPIRE
        if (d.genre == Genre.LIBRE) return Statut.A_VERIFIER
        return if (compte >= d.objectif) Statut.OBTENABLE else Statut.EN_COURS
    }

    fun libelle(d: Diplome, st: Statut, compte: Int, s: Suivi, t: Long, tz: TimeZone = TimeZone.getDefault()): String = when (st) {
        Statut.OBTENABLE -> "Obtenable — " + when {
            d.limiteMs > 0 -> "à demander avant le " + jour(d.limiteMs, tz)
            d.evenement -> "$compte image${if (compte > 1) "s" else ""} reçue${if (compte > 1) "s" else ""}, à demander dès que possible"
            else -> "objectif atteint : $compte/${d.objectif} ${d.genre.unite}"
        }
        Statut.EN_COURS -> "En cours : $compte/${d.objectif} ${d.genre.unite}"
        Statut.A_VENIR -> "À venir : du " + jour(d.debutMs, tz) + " au " + jour(d.finMs, tz)
        Statut.PENDANT -> "Événement en cours jusqu'au " + jour(d.finMs, tz) + " : aucune image reçue encore"
        Statut.DEMANDE -> "Demandé" + (if (s.quandMs > 0) " le " + jour(s.quandMs, tz) else "")
        Statut.RECU -> "Reçu" + (if (s.quandMs > 0) " le " + jour(s.quandMs, tz) else "")
        Statut.EXPIRE -> "Expiré" + (if (d.limiteMs > 0) " (demandes closes le " + jour(d.limiteMs, tz) + ")" else "")
        Statut.MANQUE -> "Aucune image reçue pendant l'événement"
        Statut.A_VERIFIER -> "Conditions à vérifier sur la page de l'émetteur"
        Statut.MASQUE -> "Masqué"
    }

    // ------------------------------------------------------------------ what to announce

    /** A notice to give once: its key (remembered), its words, the award. */
    data class Avis(val cle: String, val sorte: String, val texte: String, val id: String)

    /**
     * What to tell at [t]: an award newly obtainable (once), its deadline
     * within [joursAvant] days (once), a new event award announced online
     * (once, when [nouveaux]). [deja] are the keys already told.
     */
    fun aSignaler(bilans: List<Bilan>, deja: Set<String>, t: Long, joursAvant: Int, obtenables: Boolean = true, echeances: Boolean = true,
                  nouveaux: Boolean = true, tz: TimeZone = TimeZone.getDefault()): List<Avis> {
        val l = ArrayList<Avis>()
        for (b in bilans) {
            val d = b.diplome
            if (obtenables && b.statut == Statut.OBTENABLE) {
                val k = "obtenable:" + d.id
                if (k !in deja) l += Avis(k, "obtenable", "Diplôme obtenable : " + d.nom + " — " + b.libelle.removePrefix("Obtenable — "), d.id)
            }
            if (echeances && b.statut == Statut.OBTENABLE && d.limiteMs > t && d.limiteMs - t <= joursAvant * JOUR) {
                val k = "echeance:" + d.id
                if (k !in deja) {
                    val j = ((d.limiteMs - t + JOUR - 1) / JOUR).toInt()
                    l += Avis(k, "echeance", "Plus que " + (if (j <= 1) "un jour" else "$j jours") + " pour demander : " + d.nom + " (avant le " + jour(d.limiteMs, tz) + ")", d.id)
                }
            }
            if (nouveaux && d.evenement && !d.id.startsWith("cat:") && (b.statut == Statut.A_VENIR || b.statut == Statut.PENDANT)) {
                val k = "nouveau:" + d.id
                if (k !in deja) l += Avis(k, "nouveau", "Nouveau diplôme annoncé : " + d.nom + " — " + d.emetteur, d.id)
            }
        }
        return l
    }

    // ------------------------------------------------------------------ reading an announcement

    private val MOT_DIPLOME = Regex("\\b(awards?|certificates?|certificados?|certificats?|diplomas?|dipl[oô]mes?|trophy|troph[ée]e)\\b", RegexOption.IGNORE_CASE)
    private val ORGA = Regex("(radio\\s*club|radioclub|radio\\s+operadores|radioaficionados|asociaci[oó]n|association|amicale|soci[eé]t[eé]|society|club|liga|league|\\bLABRE\\b|\\bARRL\\b|\\bREF\\b|AMSAT[-\\w]*|\\bARISS\\b)", RegexOption.IGNORE_CASE)
    private val INDICATIF = Regex("\\b(?:[A-Z]{1,2}|[A-Z][0-9]|[0-9][A-Z])[0-9][A-Z]{1,4}\\b")
    private val LIMITE = Regex("(request\\s+deadline|deadline|date\\s+limite(?:\\s+de\\s+(?:soumission|demande|r[ée]ception|candidature))?|fecha\\s+l[ií]mite|prazo|" +
        "requests?\\s+(?:are\\s+)?(?:accepted\\s+)?(?:until|by|before)|applications?\\s+(?:are\\s+)?(?:accepted\\s+)?(?:until|by|before)|apply\\s+(?:by|before)|submit\\s+(?:by|before)|" +
        "[àa]\\s+demander\\s+avant|avant\\s+le|no\\s+later\\s+than|hasta\\s+el)\\s*[:\\-]?\\s*", RegexOption.IGNORE_CASE)
    private val RELATIF = Regex("(?:jusqu.?[àa]\\s*|up\\s+to\\s+|within\\s+|hasta\\s+|at[ée]\\s+)?(\\d{1,3})\\s*(heures?|hours?|hrs?|h|jours?|days?|d[ií]as|dias)\\s+(?:apr[eè]s|after|despu[eé]s|ap[oó]s)",
        RegexOption.IGNORE_CASE)
    private val SERIE = Regex("ARISS\\s+SSTV\\s+(?:SERIES|SERIE|S[ÉE]RIE)\\s*(\\d{1,3})", RegexOption.IGNORE_CASE)
    private val COURT = Regex("(?:https?\\s?:\\s?//\\s?)?(?:www\\.)?(?:bit\\.ly|forms\\.gle|tinyurl\\.com|goo\\.gl|t\\.ly|docs\\.google\\.com/forms|forms\\.office\\.com)/[A-Za-z0-9_./?=&-]+", RegexOption.IGNORE_CASE)
    private val LIEN = Regex("https?\\s?:\\s?//\\s?[^\\s<>\"']+", RegexOption.IGNORE_CASE)
    /** The ARISS gallery, where its award is asked. */
    private val GALERIE = Regex("SSTV\\s+gallery|ARISS_SSTV|ARISS\\s+gallery|galerie\\s+(?:SSTV\\s+)?(?:de\\s+l.)?ARISS", RegexOption.IGNORE_CASE)
    /** A line promising an award to whoever receives (not telling who got one). */
    private val PROMESSE = Regex("receiv|re[cç]e?v|recib|d[ée]cod|upload|submit|envoy|soumet|apply|request|demand|solicit|eligible|grant|auront\\s+droit|otorga|participa",
        RegexOption.IGNORE_CASE)
    private val UN_SEUL = Regex("only\\s+one\\s+award|one\\s+award\\s+per|un\\s+seul\\s+(?:dipl[oô]me|certificat)|un\\s+solo\\s+(?:diploma|certificado)|uno\\s+por\\s+participante", RegexOption.IGNORE_CASE)
    private val SWL = Regex("\\bSWL\\b|no\\s+(?:amateur\\s+radio\\s+)?licen[cs]e|pas\\s+(?:n[ée]cessaire\\s+)?d.avoir\\s+un\\s+indicatif|sin\\s+licencia", RegexOption.IGNORE_CASE)

    private val LIAISONS = setOf("de", "del", "des", "du", "la", "le", "les", "of", "the", "y", "do", "da", "di", "et", "and", "&")

    /** An organisation's name from where it starts: its capitalised words (and « de », « of »…), up to a verb, a comma. */
    private fun nomOrga(l: String): String {
        val mots = ArrayList<String>()
        for (w in l.split(Regex("\\s+"))) {
            val m = w.trimEnd(',', ';', ':', '.', '!')
            if (m.isEmpty()) break
            if (m.first().isUpperCase() || m.first().isDigit() || m.lowercase() in LIAISONS || m == "A.C" || m == "Inc") mots += m else break
            if (w.endsWith(',') || w.endsWith(';') || w.endsWith(':') || mots.size >= 7) break
        }
        while (mots.isNotEmpty() && mots.last().lowercase() in LIAISONS) mots.removeAt(mots.size - 1)
        return propre(mots.joinToString(" ")).take(50)
    }

    /** What [lisAnnonce] found: the award proposed, said for the owner. */
    data class Lecture(val diplome: Diplome, val trouve: List<String>, val estDiplome: Boolean)

    private fun slug(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)

    private fun propre(l: String) = l.replace(Regex("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}.)]+$"), "").replace(Regex("\\s+"), " ").trim()

    private fun titre(l: String): String = if (l.any { it.isLowerCase() }) l else
        l.lowercase().split(' ').joinToString(" ") { w -> if (INDICATIF.matches(w.uppercase()) || w.uppercase() in setOf("ARISS", "SSTV", "ISS", "AMSAT", "LABRE", "ARRL", "REF")) w.uppercase() else w.replaceFirstChar { it.titlecase() } }

    private fun jourUtc(ms: Long) = SimpleDateFormat("yyyyMMdd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))

    /**
     * A flyer's text (OCR) or a pasted announcement read as an award: who
     * gives it, for which event (its window, satellite, frequency, mode),
     * until when to ask (a date, or « 48 heures après la fin »), the form's
     * link. [maintenant] and [tz] place a date without its year.
     */
    fun lisAnnonce(texte: String, maintenant: Long, tz: TimeZone = TimeZone.getDefault(), satellites: List<Pair<String, Int>> = emptyList(),
                   source: String = "Affiche", sourceLien: String = ""): Lecture {
        val trouve = ArrayList<String>()
        var lignes = texte.replace('’', '\'').replace('–', '-').replace('—', '-').replace('•', ' ').lines().map { it.trim() }.filter { it.isNotEmpty() }
        // « 22 de octubre de 2026 » → « 22 octubre, 2026 »; a month alone on its line joined to the day under it.
        val mois = AfficheSstv.NOMS_MOIS
        lignes = lignes.map {
            it.replace(Regex("\\b(around|approximately|approx\\.?|about|circa|vers)\\s+(?=\\d{1,2}[:h]\\d{2})", RegexOption.IGNORE_CASE), "at ")
                .replace(Regex("\\b(\\d{1,2})\\s+de\\s+($mois)\\b", RegexOption.IGNORE_CASE), "$1 $2")
                .replace(Regex("\\b($mois)\\s+de\\s+(\\d{4})\\b", RegexOption.IGNORE_CASE), "$1, $2")
        }
        val jointes = ArrayList<String>()
        var i = 0
        while (i < lignes.size) {
            val l = lignes[i]
            // « OCTOBER » alone (an OCR may leave a speck before it: « fh OCTOBER »).
            val chiffre = lignes.getOrNull(i + 1)?.indexOfFirst { it.isDigit() } ?: -1
            if (l.length <= 16 && Regex("\\b($mois)\\W*$", RegexOption.IGNORE_CASE).containsMatchIn(l) && chiffre in 0..3) {
                jointes += Regex("\\b($mois)\\W*$", RegexOption.IGNORE_CASE).find(l)!!.groupValues[1] + " " + lignes[i + 1].substring(chiffre); i += 2
            } else { jointes += l; i++ }
        }
        // A range (« 22-25 Octubre 2026 », « October 22 - 25, 2026 ») written as its two dates, so a third date elsewhere does not hide it.
        for (k in jointes.indices) {
            jointes[k] = jointes[k].replace(Regex("\\b(\\d{1,2})\\s*-\\s*(\\d{1,2})\\s+($mois)\\b\\.?(?:[,\\s]+(\\d{4})\\b)?", RegexOption.IGNORE_CASE)) { m ->
                val (a, b, mo, an) = m.destructured
                val y = if (an.isNotEmpty()) " $an" else ""
                "$a $mo$y - $b $mo$y"
            }.replace(Regex("\\b($mois)\\.?\\s+(\\d{1,2})\\s*-\\s*(\\d{1,2})\\b(?:,?\\s*(\\d{4})\\b)?", RegexOption.IGNORE_CASE)) { m ->
                val (mo, a, b, an) = m.destructured
                val y = if (an.isNotEmpty()) ", $an" else ""
                "$mo $a$y - $mo $b$y"
            }
        }
        // The deadline: its words, then a date or a delay after the end (« jusqu'à 48 heures après la fin »).
        var limiteAbs = 0L
        var limiteRel = 0L
        val sansLimite = ArrayList(jointes)
        for ((k, l) in jointes.withIndex()) {
            val m = LIMITE.find(l) ?: continue
            val apres = l.substring(m.range.last + 1)
            val suite = if (apres.any { it.isDigit() } || k + 1 >= jointes.size) apres else apres + " " + jointes[k + 1]
            val r = RELATIF.find(suite)
            if (r != null) {
                val n = r.groupValues[1].toLong()
                limiteRel = n * (if (r.groupValues[2].startsWith("h", true)) 3_600_000L else JOUR)
            } else {
                val p = AfficheSstv.lis(suite, maintenant, tz)
                if (p.debutMs > 0) limiteAbs = if (p.aConfirmer) p.debutMs + JOUR - 1 else p.debutMs
            }
            if (limiteAbs > 0 || limiteRel > 0) {
                sansLimite[k] = l.substring(0, m.range.first)
                if (!apres.any { it.isDigit() } && k + 1 < jointes.size) sansLimite[k + 1] = ""
                break
            }
        }
        val corps = sansLimite.filter { it.isNotBlank() }.joinToString("\n")
        val p = AfficheSstv.lis(corps, maintenant, tz, satellites)
        val tout = jointes.joinToString("\n")
        val estDiplome = MOT_DIPLOME.containsMatchIn(tout)
        if (estDiplome) trouve += "diplôme annoncé"
        val (satNom, catnum) = if (p.catnum > 0) (satellites.firstOrNull { it.second == p.catnum }?.first ?: p.satNom) to p.catnum
            else LectureActivites.nomCite(tout).let { cite ->
                val n = NomsSatellites.identifie(cite, jointes.take(12).joinToString(" "), satellites)
                if (n > 0) (satellites.first { it.second == n }.first) to n else cite to 0
            }
        if (satNom.isNotBlank()) trouve += "satellite $satNom"
        val de = p.debutMs; val a = if (p.finMs > 0) p.finMs else p.debutMs
        if (de > 0) trouve += "période"
        val limite = when {
            limiteAbs > 0 -> limiteAbs
            limiteRel > 0 && a > 0 -> a + limiteRel
            else -> 0L
        }
        if (limite > 0) trouve += "date limite"
        // The event: « ARISS SSTV Series 34 », else what the flyer calls it.
        val evenement = SERIE.find(tout.replace('\n', ' '))?.let { "ARISS SSTV Series " + it.groupValues[1] } ?: p.nom
        // Who gives it: a club's line (its callsign beside), else the source.
        // ARISS is the event's, not the club's: a line naming another organisation first.
        val lOrga = jointes.indexOfFirst { l -> ORGA.findAll(l).any { !it.value.equals("ARISS", true) } }
        var emetteur = ""
        if (lOrga >= 0) {
            val l = jointes[lOrga]
            emetteur = nomOrga(l.substring(ORGA.findAll(l).first { !it.value.equals("ARISS", true) }.range.first))
            val ind = (listOf(l) + listOfNotNull(jointes.getOrNull(lOrga + 1), jointes.getOrNull(lOrga - 1))).firstNotNullOfOrNull { INDICATIF.find(it)?.value }
                ?: jointes.firstNotNullOfOrNull { x -> if (ORGA.containsMatchIn(x)) INDICATIF.find(x)?.value else null }
                ?: INDICATIF.findAll(tout).firstOrNull { m -> !Regex("(?:otorgado\\s+a|awarded\\s+to|issued\\s+to|d[ée]cern[ée]\\s+[àa]|attribu[ée]\\s+[àa])\\s*:?\\s*$", RegexOption.IGNORE_CASE)
                    .containsMatchIn(tout.substring(maxOf(0, m.range.first - 20), m.range.first)) }?.value
            if (ind != null && !emetteur.contains(ind)) emetteur = "$emetteur $ind".trim()
        }
        // The ARISS gallery's certificate, not a club's (no club with its callsign named).
        val galerie = GALERIE.containsMatchIn(tout) && !(lOrga >= 0 && INDICATIF.containsMatchIn(emetteur) && !Regex("ISS\\b").containsMatchIn(emetteur))
        if (galerie) emetteur = "ARISS"
        if (emetteur.isBlank()) emetteur = if (Regex("\\bARISS\\b").containsMatchIn(tout)) "ARISS" else source
        else trouve += "émetteur $emetteur"
        // The award's own words: « Participation award », « Certificado especial ARISS SSTV Serie 34 ».
        val lDip = jointes.firstOrNull { MOT_DIPLOME.containsMatchIn(it) && it.length <= 70 && !LIMITE.containsMatchIn(it) }
        val quoi = lDip?.let { titre(propre(it)) }.orEmpty()
        val nom = when {
            galerie -> "ARISS SSTV Award" + (Regex("Series\\s+(\\d{1,3})", RegexOption.IGNORE_CASE).find(tout)?.let { " — Series " + it.groupValues[1] } ?: "")
            quoi.isNotBlank() && evenement.isNotBlank() && !quoi.contains(evenement, true) &&
                !(SERIE.containsMatchIn(quoi) || Regex("s[ée]rie\\s*\\d", RegexOption.IGNORE_CASE).containsMatchIn(quoi)) -> "$quoi — $evenement"
            quoi.isNotBlank() -> quoi
            evenement.isNotBlank() -> "Diplôme — $evenement"
            else -> "Diplôme"
        }.take(90)
        // The links: the form (a short link, a forms page), the rules.
        val courts = COURT.findAll(tout.replace(Regex("(https?)\\s?:\\s?//\\s?"), "$1://")).map { it.value.trimEnd('.', ',', ')') }
            .map { if (it.startsWith("http", true)) it else "https://$it" }.toList()
        val liens = LIEN.findAll(tout).map { it.value.replace(" ", "").trimEnd('.', ',', ')') }.filter { CampagneSstv.lienPropre(it).isNotEmpty() }.toList()
        val formulaire = courts.firstOrNull() ?: liens.firstOrNull { Regex("submit|form|formulario|formulaire|apply|request|solicitud", RegexOption.IGNORE_CASE).containsMatchIn(it) } ?: ""
        val reglement = liens.firstOrNull { it != formulaire } ?: sourceLien
        if (formulaire.isNotBlank()) trouve += "formulaire"
        val un = UN_SEUL.containsMatchIn(tout)
        val notes = listOfNotNull(if (SWL.containsMatchIn(tout)) "Ouvert aux écouteurs (SWL)." else null,
            if (un) "Un seul diplôme par participant." else null,
            if (limiteRel > 0) "Date limite : " + (limiteRel / 3_600_000L) + " h après la fin des émissions." else null).joinToString(" ")
        val conditions = when {
            Regex("50\\s*%", RegexOption.IGNORE_CASE).containsMatchIn(tout) -> "Recevoir une image de l'événement (au moins 50 % lisible), fichier original sans retouche."
            else -> "Recevoir au moins une image de l'événement" + (if (evenement.isNotBlank()) " (" + evenement + ")" else "") + ", puis la demander avec le formulaire de l'émetteur."
        }
        val id = (if (source == "Affiche") "affiche:" else "annonce:") + slug(emetteur.ifBlank { nom }) + "-" + (if (de > 0) jourUtc(de) else slug(nom))
        val d = Diplome(id = id, nom = nom, emetteur = emetteur, genre = Genre.IMAGE, objectif = 1, satNom = satNom, catnum = catnum,
            debutMs = de, finMs = a, limiteMs = limite, conditions = conditions, lienReglement = reglement, lienFormulaire = formulaire,
            champs = listOf("indicatif", "nom", "courriel", "image", "date", "heure"), source = source, sourceLien = sourceLien,
            freqHz = p.freqHz, mode = p.modeSstv.takeIf { it != CampagneSstv.AUTO }.orEmpty(), unParParticipant = un, notes = notes)
        return Lecture(d, trouve, estDiplome)
    }

    // ------------------------------------------------------------------ the ARISS SSTV award's page

    private val SESSION = Regex("Exp\\w*dition\\s+(\\d{2,3}(?:-\\d)?)\\s*\\(\\s*(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})\\s*-\\s*(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})\\s*\\)", RegexOption.IGNORE_CASE)

    /**
     * https://ariss.pzk.org.pl/sstv/: each session's award (« Expedition 75-2
     * (22.10.2026 - 25.10.2026) », its theme), those ended less than [ageMaxMs]
     * ago or to come. Its news gives the frequency, mode and series number of
     * the session it speaks of. Comments (old text kept in the page) skipped.
     */
    fun lisPzk(html: String, maintenant: Long, ageMaxMs: Long = 60 * JOUR): List<Diplome> {
        val s = html.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")
        val nouvelles = Regex("id=\"news_en\"(.*?)(?:id=\"award_rules|$)", RegexOption.DOT_MATCHES_ALL).find(s)?.groupValues?.get(1)?.let(LectureActivites::texte).orEmpty()
        val pNouv = if (nouvelles.isNotBlank()) AfficheSstv.lis(nouvelles, maintenant, TimeZone.getTimeZone("UTC")) else null
        val serieNouv = Regex("Serie[s]?\\s+(\\d{1,3})", RegexOption.IGNORE_CASE).find(nouvelles)?.groupValues?.get(1)
        val utc = TimeZone.getTimeZone("UTC")
        fun date(j: String, m: String, a: String, fin: Boolean) = Calendar.getInstance(utc).apply {
            clear(); set(a.toInt(), m.toInt() - 1, j.toInt()); if (fin) { set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59) }
        }.timeInMillis
        return SESSION.findAll(s).mapNotNull { m ->
            val g = m.groupValues
            val de = runCatching { date(g[2], g[3], g[4], false) }.getOrNull() ?: return@mapNotNull null
            val a = runCatching { date(g[5], g[6], g[7], true) }.getOrNull() ?: return@mapNotNull null
            if (a < maintenant - ageMaxMs || a < de) return@mapNotNull null
            val suite = LectureActivites.texte(s.substring(m.range.last + 1, minOf(s.length, m.range.last + 1500)))
            val theme = Regex("ARISS\\s+SSTV\\s+Award\\s*[\"“«]\\s*([^\"”»]{2,60}?)\\s*[\"”»]", RegexOption.IGNORE_CASE).find(suite)?.groupValues?.get(1)
            val memeNouv = pNouv != null && pNouv.debutMs > 0 && pNouv.debutMs <= a + JOUR && pNouv.finMs >= de - JOUR
            val serie = if (memeNouv) serieNouv else null
            Diplome(id = "pzk:" + g[1], nom = "ARISS SSTV Award" + (theme?.let { " « $it »" } ?: "") + (if (serie != null) " — Series $serie" else "") + " (Expedition " + g[1] + ")",
                emetteur = "ARISS (diplôme géré par le PZK)", genre = Genre.IMAGE, objectif = 1, satNom = "ISS", catnum = 25544,
                debutMs = de, finMs = a, conditions = CONDITIONS_ARISS, lienReglement = PZK, lienFormulaire = GALERIE_ARISS,
                frais = "gratuit (aucun frais indiqué)", champs = listOf("courriel", "nom", "indicatif", "image", "date", "heure", "locator"),
                source = "ariss.pzk.org.pl", sourceLien = PZK, verifie = "27/10/2026",
                freqHz = if (memeNouv) pNouv!!.freqHz else 0L, mode = if (memeNouv) pNouv!!.modeSstv.takeIf { it != CampagneSstv.AUTO }.orEmpty() else "")
        }.distinctBy { it.id }.toList()
    }

    /** An ARISS SSTV session known from the calendar or a campaign only (the award's page not read): its award. */
    fun depuisSessionAriss(titre: String, debutMs: Long, finMs: Long, freqHz: Long = 0L, mode: String = ""): Diplome =
        Diplome(id = "ariss:" + jourUtc(debutMs), nom = "ARISS SSTV Award — " + titre.take(60), emetteur = "ARISS (diplôme géré par le PZK)",
            genre = Genre.IMAGE, satNom = "ISS", catnum = 25544, debutMs = debutMs, finMs = finMs, conditions = CONDITIONS_ARISS,
            lienReglement = PZK, lienFormulaire = GALERIE_ARISS, frais = "gratuit (aucun frais indiqué)",
            champs = listOf("courriel", "nom", "indicatif", "image", "date", "heure", "locator"), source = "Agenda ARISS", sourceLien = PZK,
            freqHz = freqHz, mode = mode)

    /** ARISS's own award (« ARISS » issuer), not a club's. */
    fun estAriss(d: Diplome) = d.emetteur.startsWith("ARISS") && d.evenement

    /**
     * One award per session: an ARISS award known twice (its page, the
     * calendar, a news item) kept once — the page's first; a club's award
     * always kept. The ids given earlier ([ordre]) win.
     */
    fun fusionne(l: List<Diplome>): List<Diplome> {
        val r = ArrayList<Diplome>()
        val rang = { d: Diplome -> when { d.id.startsWith("pzk:") -> 0; d.id.startsWith("ariss:") -> 1; else -> 2 } }
        for (d in l.sortedBy(rang)) {
            if (r.any { it.id == d.id }) continue
            if (estAriss(d) && r.any { estAriss(it) && it.debutMs <= d.finMs + JOUR && it.finMs >= d.debutMs - JOUR }) continue
            r += d
        }
        return r
    }

    // ------------------------------------------------------------------ news feeds

    private val CONTEXTE = Regex("\\bSSTV\\b|\\bimages?\\b|\\bpictures?\\b|d[ée]cod|re[cç]e?v|receiv|recib|SWL|satellite|\\bISS\\b", RegexOption.IGNORE_CASE)
    /** A line that tells someone earned an award, not that one can be earned. */
    private val PALMARES = Regex("\\b(earns?|earned|awarded\\s+(?:to|a|the)|re[cç]oit\\s+le|a\\s+obtenu)\\b|#\\d+", RegexOption.IGNORE_CASE)

    /** A bulletin's heading: short, no full stop, a few words, not a link nor a credit. */
    private fun titreDeNouvelle(l0: String): Boolean {
        val l = l0.removePrefix("+").trim()
        return l.length in 8..100 && !l.trimEnd().endsWith('.') && !l.startsWith("http") && !l.startsWith("[") && !l.contains(';') &&
            l.count { it == ' ' } >= 2 && l.first().isUpperCase() && !Regex("^(Begins?|Concludes?|Starts?|Ends?|D[ée]but|Fin)\\b").containsMatchIn(l)
    }

    /**
     * A news feed's posts announcing an award to earn by receiving (« will
     * receive an event certificate », « certificat spécial »), published
     * within [ageMaxMs]: each read as an announcement ([lisAnnonce]) on the
     * part of the post that speaks of it (a weekly bulletin holds many news).
     */
    fun lisRss(xml: String, source: String, sourceLien: String, maintenant: Long, satellites: List<Pair<String, Int>> = emptyList(),
               ageMaxMs: Long = 45 * JOUR): List<Diplome> {
        val items = Regex("<(item|entry)\\b[^>]*>(.*?)</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(xml).map { it.groupValues[2] }
        val r = ArrayList<Diplome>()
        for (it in items) {
            val titre = LectureActivites.texte(LectureActivites.balise(it, "title").orEmpty()).replace('\n', ' ')
            val html = LectureActivites.balise(it, "content:encoded") ?: LectureActivites.balise(it, "content") ?: LectureActivites.balise(it, "description") ?: LectureActivites.balise(it, "summary") ?: ""
            val publie = (LectureActivites.balise(it, "pubDate") ?: LectureActivites.balise(it, "published") ?: LectureActivites.balise(it, "updated"))
                ?.let(LectureActivites::datePost) ?: continue
            if (publie < maintenant - ageMaxMs) continue
            val lien = LectureActivites.balise(it, "link")?.trim()?.takeIf { l -> l.startsWith("http") }
                ?: Regex("<link[^>]*href=\"([^\"]+)\"").find(it)?.groupValues?.get(1) ?: sourceLien
            val lignes = (listOf(titre) + LectureActivites.texte(html).lines()).filter { l -> l.isNotBlank() }
            // The lines that promise an award for receiving, and the news around each (from its heading).
            val vus = HashSet<Int>()
            for ((k, l) in lignes.withIndex()) {
                // One sentence holding the award, the promise and the pictures (« who upload images … will receive an event certificate »).
                if (l.split(Regex("(?<=[.!?])\\s+")).none { ph -> MOT_DIPLOME.containsMatchIn(ph) && PROMESSE.containsMatchIn(ph) && !PALMARES.containsMatchIn(ph) &&
                        Regex("\\bSSTV\\b|images?|pictures?|imagens?|im[áa]genes|d[ée]cod", RegexOption.IGNORE_CASE).containsMatchIn(ph) }) continue
                val de = (k downTo maxOf(0, k - 12)).firstOrNull { j -> j < k && titreDeNouvelle(lignes[j]) } ?: maxOf(0, k - 6)
                val fin = ((k + 1) until minOf(lignes.size, k + 8)).firstOrNull { j -> titreDeNouvelle(lignes[j]) } ?: minOf(lignes.size, k + 8)
                // The post's title (« ISS Slow Scan TV Oct 22-26 ») would give its dates without their hours: the body's first.
                val bloc = lignes.subList(if (de == 0 && k > 0 && lignes.size > 1) 1 else de, fin)
                val texte = bloc.joinToString("\n")
                if (!CONTEXTE.containsMatchIn(texte) || PALMARES.containsMatchIn(bloc.first())) continue
                if (!Regex("\\bSSTV\\b|slow[\\s-]?scan|images?|pictures?", RegexOption.IGNORE_CASE).containsMatchIn(texte)) continue
                if (!vus.add(de)) continue
                val lu = lisAnnonce(texte, publie, TimeZone.getTimeZone("UTC"), satellites, source, lien)
                if (!lu.estDiplome || lu.diplome.debutMs <= 0) continue
                // A period over long before the post: an old event recalled.
                if (lu.diplome.finMs < publie - 30 * JOUR) continue
                val nom = if (lu.diplome.nom.startsWith("Diplôme") || lu.diplome.nom.length < 12) (bloc.first().take(80)) else lu.diplome.nom
                r += lu.diplome.copy(id = "rss:" + lien + (if (vus.size > 1) "#$de" else ""), nom = nom, lienReglement = lien.ifBlank { lu.diplome.lienReglement })
            }
        }
        return r
    }

    // ------------------------------------------------------------------ the file

    data class Reglages(val actif: Boolean = true, val obtenable: Boolean = true, val echeance: Boolean = true, val nouveau: Boolean = true,
                        val jours: Int = 3, val sources: Boolean = true)

    /** What the request forms ask that the settings do not hold: kept here, given by the owner. */
    data class Identite(val nom: String = "", val courriel: String = "")

    /** `diplomes.json`: the awards found or added, the owner's follow-up, what was told. */
    data class Fichier(
        val reglages: Reglages = Reglages(),
        val identite: Identite = Identite(),
        /** Awards found online or added (a flyer, a text), kept once their source forgets them. */
        val connus: List<Diplome> = emptyList(),
        val suivis: Map<String, Suivi> = emptyMap(),
        val dits: Set<String> = emptySet(),
    )

    fun json(d: Diplome): JSONObject = JSONObject().put("id", d.id).put("nom", d.nom).put("emetteur", d.emetteur).put("genre", d.genre.name)
        .put("objectif", d.objectif).put("satNom", d.satNom).put("catnum", d.catnum).put("de", d.debutMs).put("a", d.finMs).put("limite", d.limiteMs)
        .put("conditions", d.conditions).put("reglement", d.lienReglement).put("formulaire", d.lienFormulaire).put("courrielDemande", d.courrielDemande)
        .put("frais", d.frais).put("champs", JSONArray(d.champs)).put("source", d.source).put("sourceLien", d.sourceLien).put("verifie", d.verifie)
        .put("freqHz", d.freqHz).put("mode", d.mode).put("unParParticipant", d.unParParticipant).put("notes", d.notes).put("fictif", d.fictif)

    fun lis(o: JSONObject): Diplome? {
        val id = o.optString("id").takeIf { it.isNotBlank() && it.length <= 300 } ?: return null
        val genre = runCatching { Genre.valueOf(o.optString("genre")) }.getOrNull() ?: return null
        val ch = o.optJSONArray("champs") ?: JSONArray()
        return Diplome(id, o.optString("nom").take(120).ifBlank { "Diplôme" }, o.optString("emetteur").take(80), genre, o.optInt("objectif", 1).coerceIn(1, 100_000),
            o.optString("satNom").take(40), o.optInt("catnum"), o.optLong("de"), o.optLong("a"), o.optLong("limite"), o.optString("conditions").take(800),
            CampagneSstv.lienPropre(o.optString("reglement")), CampagneSstv.lienPropre(o.optString("formulaire")), o.optString("courrielDemande").take(100),
            o.optString("frais").take(200), (0 until ch.length()).map { ch.optString(it) }.filter { it.isNotBlank() }.take(12),
            o.optString("source").take(60), CampagneSstv.lienPropre(o.optString("sourceLien")), o.optString("verifie").take(20),
            o.optLong("freqHz"), o.optString("mode").take(20), o.optBoolean("unParParticipant"), o.optString("notes").take(600), o.optBoolean("fictif"))
    }

    fun ecrit(f: Fichier): JSONObject {
        val r = f.reglages
        val su = JSONObject()
        for ((id, s) in f.suivis) su.put(id, JSONObject().put("etat", s.etat.name).put("quand", s.quandMs).put("certificat", s.certificat).put("note", s.note))
        return JSONObject().put("v", 1)
            .put("reglages", JSONObject().put("actif", r.actif).put("obtenable", r.obtenable).put("echeance", r.echeance).put("nouveau", r.nouveau)
                .put("jours", r.jours).put("sources", r.sources))
            .put("identite", JSONObject().put("nom", f.identite.nom).put("courriel", f.identite.courriel))
            .put("connus", JSONArray(f.connus.map(::json))).put("suivis", su).put("dits", JSONArray(f.dits.toList().takeLast(400)))
    }

    fun lisFichier(texte: String): Fichier = runCatching {
        val o = JSONObject(texte)
        val r = o.optJSONObject("reglages") ?: JSONObject()
        val i = o.optJSONObject("identite") ?: JSONObject()
        val c = o.optJSONArray("connus") ?: JSONArray()
        val su = o.optJSONObject("suivis") ?: JSONObject()
        val di = o.optJSONArray("dits") ?: JSONArray()
        Fichier(Reglages(r.optBoolean("actif", true), r.optBoolean("obtenable", true), r.optBoolean("echeance", true), r.optBoolean("nouveau", true),
                r.optInt("jours", 3).coerceIn(1, 60), r.optBoolean("sources", true)),
            Identite(i.optString("nom").take(80), i.optString("courriel").take(120)),
            (0 until c.length()).mapNotNull { c.optJSONObject(it)?.let(::lis) },
            su.keys().asSequence().mapNotNull { k -> su.optJSONObject(k)?.let { s ->
                k to Suivi(runCatching { EtatSuivi.valueOf(s.optString("etat")) }.getOrDefault(EtatSuivi.AUCUN), s.optLong("quand"),
                    s.optString("certificat").take(120), s.optString("note").take(300))
            } }.toMap(),
            (0 until di.length()).map { di.optString(it) }.filter { it.isNotBlank() }.toSet())
    }.getOrDefault(Fichier())
}
