/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import fr.f4ioz.satcombo.domain.CampagneSstv
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.station.ActiviteMontage.Etat
import fr.f4ioz.satcombo.station.FonctionsMontage.Statut
import fr.f4ioz.satcombo.station.SceneMontage.Pinceau
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * A plan of Station › Montage drawn away from the page, as the page draws
 * it: the same layout ([DispositionMontage]), the same drawings of the
 * hardware ([DessinsMateriel]) or the owner's cut-out photos, the cables in
 * their colours with their labels, what works lit; right of the computer,
 * SatMe's functions in their columns (the hardware held and the sound
 * cards, the sound's sources, the decoders), joined by their links.
 *
 * Made for the SSTV event's report ([pourSstv]: what the autopilot set up
 * for each pass, lit on the plan), as plain shapes ([SceneMontage]): the
 * report's page writes them as SVG, its PDF draws them, the same drawing.
 *
 * Display only: nothing is read from the station, nothing started.
 */
object DessinMontage {

    /** An owner's photo, cut out (PNG) or not: its bytes and its size in pixels. */
    data class Photo(val octets: ByteArray, val l: Int, val h: Int)

    /**
     * What to draw: the [chaine] of the plan named [nom], lit by [activite];
     * [instantane] SatMe's functions as they stood (null: the hardware only);
     * [photos] by element (shown where the owner chose his photo);
     * [quoi] what was lit, in words (for the caption).
     */
    data class Dessin(
        val nom: String,
        val chaine: Montage.Chaine,
        val activite: ActiviteMontage.Activite,
        val instantane: FonctionsMontage.Instantane?,
        val photos: Map<String, Photo> = emptyMap(),
        val quoi: String = "",
    )

    // The page's colours (Station › Montage).
    private const val FOND = 0xFF0D151C.toInt()
    private const val RX = "#3FE0C8"
    private const val TX = "#FF5A36"
    private const val OK = "#5BD68A"
    private const val DANGER = "#E5484D"
    private const val TEXTE = "#E8F0F7"
    private const val TEXTE2 = "#9AA7B4"
    private const val TEXTE3 = "#6B7C8F"
    private const val INFO = "#6F8CFF"
    private const val WARN = "#FFB454"

    /** More pieces than this: only those taking part (and the computer), as the page's simplified view. */
    const val PIECES_MAX = 12
    /** A column of the software half holds this many pills at most; the rest at rest said in one. */
    const val PAR_COLONNE = 5

    // ------------------------------------------------------------------ the SSTV event

    /**
     * What an SSTV reception by [ch] (the autopilot's chain; null: its
     * defaults, the rig's own sound) lights on [c]: the rig tuned on [mhz]
     * (its antenna of that band), its sound to the decoder, the mast
     * following. [base]: the functions as they are now (the station's sound
     * cards and their names), every other function put at rest.
     */
    fun pourSstv(nom: String, c: Montage.Chaine, ch: CampagneSstv.Chaine?, mhz: Double?, base: FonctionsMontage.Instantane?,
                 photos: Map<String, Photo> = emptyMap()): Dessin {
        val h = ch ?: CampagneSstv.Chaine()
        val source = h.source.ifBlank { CampagneSstv.POSTE }
        val i = instantaneSstv(base, h, source)
        val rx = LinkedHashSet<String>(); val relies = LinkedHashSet<String>()
        val notes = LinkedHashMap<String, String>(); val liaisons = LinkedHashMap<String, String>(); val f = LinkedHashMap<String, Double>()
        val reseau = i.sonAppareil == FonctionsMontage.SOURCE_RESEAU
        when {
            source == CampagneSstv.SDR -> rx += "sdr:" + i.sdrRecepteur
            i.estSonDuPoste(i.sonAppareil) -> {
                rx += "cat"
                liaisons["cat"] = if (reseau) "reseau" else "usb"
                notes["cat"] = (if (reseau) "son réseau → " else "son USB → ") + "SSTV"
            }
            else -> rx += "son"
        }
        if (h.poste) { rx += "cat"; if ("cat" !in liaisons) liaisons["cat"] = if (i.posteReseau) "reseau" else "usb" }
        if (h.rotor) relies += "rotor"
        if (mhz != null && mhz > 1.0) { f["ciel-rx"] = mhz; if ("cat" in rx) f["poste-rx"] = mhz }
        val quoi = when {
            source == CampagneSstv.SDR -> t("rev_montage_par_sdr")
            source == CampagneSstv.POSTE -> t("rev_montage_par_poste")
            else -> tf("rev_montage_par_carte", i.nomCarte(source))
        } + (if (mhz != null && mhz > 1.0) " · " + String.format(Locale.FRANCE, "%.3f MHz", mhz) else "") +
            (if (h.rotor) " · " + t("rev_montage_rotor") else "")
        val a = ActiviteMontage.Activite(rx = rx, relies = relies, mhz = f, notes = notes, liaisons = liaisons)
        return Dessin(nom, c, a, i, photos, quoi)
    }

    /** The functions as an SSTV reception by [h] leaves them: SSTV listening to its source, the rest at rest. */
    fun instantaneSstv(base: FonctionsMontage.Instantane?, h: CampagneSstv.Chaine, source: String): FonctionsMontage.Instantane {
        val b0 = base ?: FonctionsMontage.Instantane(sonPresent = true)
        val repos = b0.copy(sdrActif = false, sdrErreur = "", sdrPourSonde = false, sdrPourMeteor = false, sdrHautParleur = false, qo100ALaCle = false,
            datvActif = false, datvSdrTenu = "", datvVerrouille = false, datvSon = false, datvErreur = "", txDatv = "", txSstv = "", txIq = "",
            sonActif = false, sonParSdr = false, sonErreur = "", sstvActif = false, ft8Actif = false, cwActif = false, aprsActif = false,
            phonieActif = false, ecouteNavigateur = false, adsbActif = false, aisActif = false, canaux = emptyList(), sdrFichier = "",
            balayage = false, auditeurs = 0, catConnecte = h.poste, catSimule = false, sonPresent = true)
        return when (source) {
            CampagneSstv.SDR -> repos.copy(sdrPresent = true, sdrActif = true, sstvActif = true, sstvSource = "sdr", sstvSourceActive = "sdr")
            CampagneSstv.POSTE -> {
                // The rig's own sound: its card when the station knows it, else one said so.
                val carte = repos.sonDuPoste.ifBlank { POSTE_CARTE }
                val cartes = if (carte in repos.cartes) repos.cartes else listOf(carte) + repos.cartes
                repos.copy(cartes = cartes, carteDuPoste = repos.carteDuPoste.ifBlank { if (carte == POSTE_CARTE) carte else "" },
                    sonActif = true, sonAppareil = carte, sstvActif = true, sstvSource = "carte", sstvSourceActive = "carte")
            }
            else -> repos.copy(cartes = if (source in repos.cartes) repos.cartes else listOf(source) + repos.cartes,
                sonActif = true, sonAppareil = source, sstvActif = true, sstvSource = "carte", sstvSourceActive = "carte")
        }
    }

    /** The rig's sound card when the station names none (the phone, a test). */
    const val POSTE_CARTE = "Carte son du poste"

    /** Among [plans], the one that draws [d]'s activity best (ties: the list's order); null when none draws any of it. */
    fun meilleurPlan(plans: List<PlansMontage.Plan>, a: ActiviteMontage.Activite): PlansMontage.Plan? =
        plans.map { it to PlansMontage.couverture(it.chaine, a).score }.filter { it.second >= 1.0 }.maxByOrNull { it.second }?.first

    // ------------------------------------------------------------------ the scene

    /**
     * The drawing as shapes. [empile]: the software half under the hardware
     * (its own « SatMe-PC » pill, as the page draws it when the computer is
     * not in the plan) rather than right of it — for a narrow page.
     */
    fun scene(d: Dessin, empile: Boolean = false): SceneMontage.Scene {
        val c = d.chaine
        val al = ActiviteMontage.allume(c, d.activite)
        val garde = if (c.elements.size > PIECES_MAX) al.enOeuvre().takeIf { it.isNotEmpty() }
            ?.plus(c.elements.filter { Montage.modele(it)?.genre == Montage.Genre.ORDINATEUR }.map { it.id }) else null
        val pl = DispositionMontage.dispose(c, garde)
        val p = Pinceau()
        val haut = 14.0
        val vide = pl.boites.isEmpty()
        // The software half: where each pill goes.
        val log = d.instantane?.let { logiciel(it) }
        val W = 160.0; val H = 36.0; val GX = 34.0; val GY = 7.0
        val cols = log?.colonnes.orEmpty()
        val hLog = (cols.maxOfOrNull { it.size } ?: 0) * (H + GY) + 30
        val pc = pl.boites.firstOrNull { b -> Montage.modele(c.element(b.id)!!)?.genre == Montage.Genre.ORDINATEUR }
            ?: pl.boites.firstOrNull { b -> Montage.modele(c.element(b.id)!!)?.genre == Montage.Genre.TELEPHONE }
        val pcPill = log != null && (empile || pc == null || vide)
        val yLog: Double; val Ht: Double; val xPc: Double; val x0: Double
        if (log == null) { yLog = 0.0; Ht = pl.hauteur.toDouble(); xPc = 0.0; x0 = 0.0 }
        else if (empile && !vide) { yLog = pl.hauteur.toDouble(); Ht = hLog; xPc = 4.0; x0 = xPc + W + GX }
        else { yLog = 0.0; Ht = max(if (vide) 0.0 else pl.hauteur.toDouble(), hLog); xPc = if (vide) 4.0 else pl.largeur + 56.0; x0 = if (pcPill) xPc + W + GX else pl.largeur + 56.0 }
        val lLog = if (log == null) 0.0 else x0 + cols.size * (W + GX) - GX + 10
        val L = max(if (vide) 0.0 else pl.largeur.toDouble(), lLog).coerceAtLeast(120.0)
        val Htot = haut + (if (log != null && empile && !vide) pl.hauteur + Ht else max(pl.hauteur.toDouble(), Ht)) + 6

        // The panel's grid, faint.
        p.groupe {
            var x = 24.0
            while (x < L) { P("M${n(x)} 0 V${n(Htot)}", "none", "rgba(255,255,255,.025)", 1.0); x += 24 }
            var y = 24.0
            while (y < Htot) { P("M0 ${n(y)} H${n(L)}", "none", "rgba(255,255,255,.025)", 1.0); y += 24 }
        }
        p.translate(0.0, haut)
        if (!vide) {
            // The cables, the pieces, the labels: in the page's order.
            for (tr in pl.traces) cable(p, c, tr, al.liens[tr.id] ?: Etat.REPOS)
            for (b in pl.boites) piece(p, c, b, al, d.photos)
            for (tr in pl.traces) etiquette(p, c, tr, al)
            // Who holds each piece: a tag above it.
            if (d.instantane != null) for (b in pl.boites) {
                val (noms, tx) = tient(c, b.id, d.instantane) ?: continue
                val texte = "▸ " + noms.joinToString(", ")
                val w = texte.length * 5.4 + 12
                p.groupe {
                    translate(b.x + b.l / 2.0 - w / 2, b.y - 22.0)
                    R(0.0, 0.0, w, 13.0, 6.5, if (tx) "rgba(255,90,54,.2)" else "rgba(63,224,200,.14)", if (tx) TX else RX, .8)
                    T(w / 2, 9.6, texte, 9.0, if (tx) "#FFD9CF" else RX, 'm', mono = false, gras = true)
                }
            }
        }
        if (log != null) {
            p.groupe {
                translate(0.0, yLog)
                val pos = HashMap<String, Pair<Double, Double>>()
                cols.forEachIndexed { k, col ->
                    val top = max(26.0, (Ht - col.size * (H + GY)) / 2 + 10)
                    col.forEachIndexed { j, nd -> pos[nd.id] = (x0 + k * (W + GX)) to (top + j * (H + GY)) }
                    if (col.isNotEmpty()) T(x0 + k * (W + GX) + W / 2, top - 8, log.titres[k].uppercase(), 9.0, TEXTE3, 'm', mono = false, espacement = 1.0)
                }
                val pcPt: Pair<Double, Double>
                if (pcPill || pc == null) {
                    pos["pc"] = xPc to (Ht / 2 - H / 2)
                    pcPt = (xPc + W) to (Ht / 2)
                } else pcPt = (pc.x + pc.l + 4.0) to (pc.y + (pc.h - 18) / 2.0)
                for (a in log.arcs) {
                    val pa = if (a.de == "pc") pcPt else pos[a.de]?.let { (it.first + W) to (it.second + H / 2) } ?: continue
                    val q = pos[a.vers] ?: continue
                    var pb = (q.first - 2) to (q.second + H / 2)
                    if (a.de != "pc" && pos[a.de]!!.first == q.first) pb = (q.first + W) to (q.second + H / 2)
                    arc(p, if (a.de == "pc") a.copy(texte = "") else a, pa, pb)
                }
                if (pcPill || pc == null) pastille(p, FonctionsMontage.Noeud("pc", "SatMe-PC", FonctionsMontage.Genre2.MATERIEL, 0, listOf("materiel"), Statut.OK,
                    if (vide) "aucun montage dessiné" else if (empile && pc != null) t("rev_montage_pc_dessus") else "pas dans le montage"), pos["pc"]!!, W, H)
                for (col in cols) for (nd in col) pastille(p, nd, pos[nd.id]!!, W, H)
            }
        }
        return SceneMontage.Scene(L, Htot, FOND, p.formes)
    }

    private fun n(v: Double) = SceneMontage.n(v)

    // ------------------------------------------------------------------ a piece

    private fun piece(p: Pinceau, c: Montage.Chaine, b: DispositionMontage.Boite, al: ActiviteMontage.Allumage, photos: Map<String, Photo>) {
        val e = c.element(b.id) ?: return
        val m = Montage.modele(e)
        val dh = b.h - 18.0; val l = b.l.toDouble()
        val etat = al.elements[e.id] ?: Etat.REPOS
        val err = al.erreurs[e.id] != null
        val nom = Montage.nomCourt(e)
        val maxc = max(6, floor((b.l + 50) / 6.2).toInt())
        val court = coupe(nom, maxc)
        val photo = photos[e.id]
        val montrePhoto = photo != null && e.photo
        p.groupe {
            translate(b.x.toDouble(), b.y.toDouble())
            // The outline of its state (none round a photo: its glow is the picture's own).
            if (!montrePhoto) halo(this, -6.0, -6.0, l + 12, dh + 12, 9.0, etat, err)
            if (montrePhoto) {
                val lueur = when { err -> DANGER; etat == Etat.RX -> RX; etat == Etat.TX || etat == Etat.RXTX -> TX; etat == Etat.RELIE -> OK; else -> null }
                image(photo!!.octets, photo.l, photo.h, 0.0, 0.0, l, dh, lueur)
            } else groupe {
                if (etat == Etat.REPOS) opacite(.82)
                DessinsMateriel.dessine(this, m?.dessin ?: "sdr", l, dh, e.modele)
            }
            val voyant = when { err -> DANGER; etat == Etat.RX -> RX; etat == Etat.TX || etat == Etat.RXTX -> TX; etat == Etat.RELIE -> OK; else -> "#3A4450" }
            C(l - 2, -2.0, 4.0, voyant, "#0A0F14", 1.5)
            if (photo != null) groupe {
                translate(-4.0, -6.0)
                R(0.0, 0.0, 13.0, 10.0, 2.0, "#1C2833", TEXTE2, .8); C(6.5, 5.0, 2.6, "none", TEXTE2, .9)
            }
            T(l / 2, dh + 13, court, 11.0, if (etat == Etat.REPOS) TEXTE2 else TEXTE, 'm', mono = false, gras = true, max = l + 50)
            val note = al.notes[e.id].orEmpty()
            if (note.isNotBlank()) T(l / 2, dh + 24, note, 9.0, if (err) "#FF8A8D" else INFO, 'm', mono = false)
        }
    }

    /** The outline of a piece's state, glowing as on the page. */
    private fun halo(p: Pinceau, x: Double, y: Double, w: Double, h: Double, r: Double, etat: Etat, err: Boolean) {
        when {
            err -> p.R(x, y, w, h, r, "rgba(229,72,77,.06)", DANGER, 1.5, doubleArrayOf(5.0, 4.0))
            etat == Etat.RX -> { lueur(p, x, y, w, h, r, "63,224,200", .45); p.R(x, y, w, h, r, "rgba(63,224,200,.06)", RX, 1.5) }
            etat == Etat.TX || etat == Etat.RXTX -> { lueur(p, x, y, w, h, r, "255,90,54", .6); p.R(x, y, w, h, r, "rgba(255,90,54,.08)", TX, 2.0) }
            etat == Etat.RELIE -> p.R(x, y, w, h, r, null, "rgba(91,214,138,.55)", 1.5)
        }
    }

    /** A soft glow round a rounded box: a few wide strokes, fainter outwards (the page's drop shadow). */
    private fun lueur(p: Pinceau, x: Double, y: Double, w: Double, h: Double, r: Double, rgb: String, force: Double) {
        for ((e, a) in listOf(7.0 to .12, 4.5 to .2, 2.5 to .3)) p.R(x, y, w, h, r, null, "rgba($rgb,${n(a * force / .45 * .6)})", e)
    }

    // ------------------------------------------------------------------ a cable

    private val FILS = mapOf(
        Montage.Famille.USB to Triple("#8A96A2", 3.0, null), Montage.Famille.RESEAU to Triple("#6F8CFF", 2.6, doubleArrayOf(8.0, 5.0)),
        Montage.Famille.WIFI to Triple(TEXTE2, 2.4, doubleArrayOf(.5, 6.0)), Montage.Famille.AUDIO to Triple(OK, 1.5, null),
        Montage.Famille.SERIE to Triple(WARN, 1.5, null), Montage.Famille.ROTOR to Triple("#B07CFF", 1.8, doubleArrayOf(6.0, 3.0)),
        Montage.Famille.REFERENCE to Triple("#C792FF", 1.3, doubleArrayOf(3.0, 3.0)), Montage.Famille.ALIM to Triple(DANGER, 2.0, null),
        Montage.Famille.GPS to Triple("#E8C547", 2.2, null), Montage.Famille.INTERNET to Triple("#4FC3F7", 1.8, doubleArrayOf(1.0, 4.0)))

    private fun cable(p: Pinceau, c: Montage.Chaine, t: DispositionMontage.Trace, etat: Etat) {
        val l = c.liens.firstOrNull { it.id == t.id } ?: return
        if (t.points.size < 2) return
        val d = "M" + t.points.joinToString(" L") { "${it.first} ${it.second}" }
        val fam = l.cable.famille
        val rx = etat == Etat.RX; val tx = etat == Etat.TX || etat == Etat.RXTX
        p.groupe {
            if (etat == Etat.REPOS) opacite(.85)
            when (fam) {
                Montage.Famille.COAX -> {
                    P(d, "none", if (tx) "rgba(255,90,54,.3)" else if (rx) "rgba(63,224,200,.22)" else "#06090C", if (tx) 12.0 else if (rx) 11.0 else 8.0, rond = true)
                    P(d, "none", "#262E36", 5.5, rond = true)
                    P(d, "none", if (tx) TX else if (rx) RX else "#7D8893", if (tx) 2.2 else if (rx) 2.0 else 1.4, rond = true)
                }
                Montage.Famille.DIRECT -> P(d, "none", "#59636E", 7.0)
                else -> {
                    val (col, ep, ti) = FILS[fam] ?: Triple("#8A96A2", 3.0, null)
                    if (etat == Etat.RELIE) P(d, "none", "rgba(91,214,138,.25)", ep + 4, rond = true)
                    P(d, "none", col, ep, ti, rond = true)
                }
            }
            // The signal flowing: dashes over the cable lit.
            if (rx || tx) {
                val coax = fam == Montage.Famille.COAX || fam == Montage.Famille.DIRECT
                P(d, "none", if (coax) (if (tx) "#FFE1D8" else "#C8FFF6") else (if (tx) TX else RX), 2.6, doubleArrayOf(2.0, 12.0), rond = true)
            }
            val port = if (rx) RX else if (tx) TX else "#7D8893"
            C(t.de.first.toDouble(), t.de.second.toDouble(), 3.0, "#0B0F13", port, 1.2)
            C(t.vers.first.toDouble(), t.vers.second.toDouble(), 3.0, "#0B0F13", port, 1.2)
        }
    }

    /** The cables' kinds as the page writes them. */
    private val CABLES = mapOf(Montage.Cable.COAX_N to "N", Montage.Cable.COAX_SMA to "SMA", Montage.Cable.COAX_F to "F", Montage.Cable.COAX_BNC to "BNC",
        Montage.Cable.COAX_PL to "PL", Montage.Cable.USB to "USB", Montage.Cable.ETHERNET to "Réseau", Montage.Cable.WIFI to "Wi-Fi",
        Montage.Cable.AUDIO to "Audio", Montage.Cable.CAT to "CAT", Montage.Cable.PTT to "PTT", Montage.Cable.ROTOR to "Rotor",
        Montage.Cable.REFERENCE to "Réf.", Montage.Cable.ALIM to "DC", Montage.Cable.GPS to "GPS", Montage.Cable.INTERNET to "Internet")

    /** A band's name from a frequency (MHz), as the page writes it. */
    fun bande(mhz: Int?): String = when {
        mhz == null || mhz == 0 -> ""
        mhz >= 10000 -> "10 GHz"; mhz >= 2000 -> "2,4 GHz"; mhz >= 1200 -> "23 cm"; mhz >= 700 -> "$mhz MHz FI"
        mhz >= 400 -> "70 cm"; mhz >= 140 -> "2 m"; else -> "$mhz MHz"
    }

    /** A cable's label: its band or the frequency on it, a reference's frequency, else its kind; the owner's note first. Its text, whether lit, whether a reference. */
    fun etiquetteTexte(c: Montage.Chaine, l: Montage.Lien, mhz: Double?): Triple<String, Boolean, Boolean> {
        val pa = Montage.port(c, l.de); val pb = Montage.port(c, l.vers)
        val fam = l.cable.famille
        var t = when (fam) {
            Montage.Famille.COAX -> {
                val s = listOfNotNull(pa, pb).filter { it.sens == Montage.Sens.SORTIE && it.mhz != null }
                val b = if (s.size == 1) s[0].mhz else listOfNotNull(pa?.mhz, pb?.mhz).maxOrNull()
                (if (mhz != null) String.format(Locale.US, "%.3f", mhz).replace('.', ',') + " MHz" else bande(b)) + (CABLES[l.cable]?.let { " · $it" } ?: "")
            }
            Montage.Famille.REFERENCE -> {
                val s = listOfNotNull(pa, pb).firstOrNull { it.sens == Montage.Sens.SORTIE && it.mhz != null } ?: listOfNotNull(pa, pb).firstOrNull { it.mhz != null }
                if (s != null) "${s.mhz} MHz" else "Réf."
            }
            Montage.Famille.DIRECT -> ""
            else -> CABLES[l.cable].orEmpty()
        }.removePrefix(" · ")
        if (l.note.isNotBlank()) t = l.note + if (t.isNotEmpty()) " · $t" else ""
        return Triple(t, mhz != null, fam == Montage.Famille.REFERENCE)
    }

    private fun etiquette(p: Pinceau, c: Montage.Chaine, t: DispositionMontage.Trace, al: ActiviteMontage.Allumage) {
        val l = c.liens.firstOrNull { it.id == t.id } ?: return
        val et = t.etiquette ?: return
        val (texte, vif, ref) = etiquetteTexte(c, l, al.mhz[l.id])
        if (texte.isEmpty()) return
        val ic = if (ref) 12.0 else 0.0
        val w = max(24.0, texte.length * 5.6 + 10 + ic)
        p.groupe {
            translate(et.first - w / 2, et.second - 17.0)
            R(0.0, 0.0, w, 13.0, 6.5, "#0F161D", if (ref) "#C792FF" else if (vif) RX else "#2F4150", .8)
            if (ref) { C(9.0, 6.5, 4.0, "none", "#C792FF", 1.0); P("M9 3.9V6.5H11", "none", "#C792FF", 1.0, rond = true) }
            T((w + ic) / 2, 9.5, texte, 9.0, if (ref) "#E4CCFF" else if (vif) TEXTE else TEXTE2, 'm', mono = true, gras = vif)
        }
    }

    // ------------------------------------------------------------------ the software half

    /** The functions' columns (each trimmed to what works, the rest at rest said in one pill), their titles, the links kept. */
    class Logiciel(val colonnes: List<List<FonctionsMontage.Noeud>>, val titres: List<String>, val arcs: List<FonctionsMontage.Arc>)

    fun logiciel(i: FonctionsMontage.Instantane): Logiciel {
        val g = FonctionsMontage.logiciel(i)
        val vifs = g.arcs.filter { it.statut != Statut.PRET }.flatMap { listOf(it.de, it.vers) }.toSet()
        val titres = listOf(t("rev_montage_col_materiel"), t("rev_montage_col_sources"), t("rev_montage_col_decodeurs"), t("rev_montage_col_sorties"))
        val cols = ArrayList<List<FonctionsMontage.Noeud>>(); val ts = ArrayList<String>()
        for (k in 0..3) {
            val tous = g.noeuds.filter { it.colonne == k }
            val actifs = tous.filter { it.statut != Statut.PRET || it.id in vifs }.map { it.id }.toSet()
            if (actifs.isEmpty()) continue
            // The page's order kept; the ones at rest only while there is room.
            val place = (PAR_COLONNE - actifs.size).coerceAtLeast(0)
            val repos = tous.filter { it.id !in actifs }
            val montres = repos.take(if (repos.size > place) (place - 1).coerceAtLeast(0) else place).map { it.id }.toSet()
            val l = ArrayList(tous.filter { it.id in actifs || it.id in montres })
            val reste = repos.size - montres.size
            if (reste > 0) l += FonctionsMontage.Noeud("autres:$k", tf("rev_montage_autres", reste), if (k == 0) FonctionsMontage.Genre2.SON else FonctionsMontage.Genre2.FONCTION,
                k, emptyList(), Statut.PRET, t("rev_montage_arret"))
            cols += l; ts += titres[k]
        }
        val ids = cols.flatten().map { it.id }.toSet() + "pc"
        return Logiciel(cols, ts, g.arcs.filter { it.de in ids && it.vers in ids })
    }

    /** The functions holding piece [id] of [c] (their names, whether one transmits); null none. */
    private fun tient(c: Montage.Chaine, id: String, i: FonctionsMontage.Instantane): Pair<List<String>, Boolean>? {
        val e = c.element(id) ?: return null
        val roles = Montage.modele(e)?.roles ?: return null
        val r = roles.mapNotNull { FonctionsMontage.ressourceDuRole(it) }.toSet()
        val p = FonctionsMontage.possessions(i).filter { it.ressource in r }
        if (p.isEmpty()) return null
        return p.map { FonctionsMontage.fonction(it.detenteur)?.nom ?: it.detenteur }.distinct() to p.any { it.role == FonctionsMontage.Role.EMISSION }
    }

    private val BADGES = mapOf("materiel" to ("M" to "ty"), "fonction" to ("F" to "ty"), "rx" to ("RX" to "rx"), "tx" to ("TX" to "tx"),
        "son-emet" to ("son ▶" to "son"), "son-recoit" to ("◀ son" to "son"), "fictif" to ("FICTIF" to "fx"))
    private val STATUTS = mapOf(Statut.OK to "en marche", Statut.PRET to "à l’arrêt", Statut.MANQUE to "manque", Statut.OCCUPE to "occupé", Statut.ERREUR to "erreur")

    private fun largeurBadges(n: FonctionsMontage.Noeud) = n.badges.mapNotNull { BADGES[it] }.sumOf { it.first.length * 5.0 + 7 + 3 }

    private fun badges(p: Pinceau, n: FonctionsMontage.Noeud, x: Double, y: Double) {
        var w = 0.0
        for (b in n.badges) {
            val (texte, k) = BADGES[b] ?: continue
            val l = texte.length * 5.0 + 7
            val (fond, enc) = when (k) {
                "rx" -> "rgba(63,224,200,.18)" to RX; "tx" -> "rgba(255,90,54,.22)" to "#FF9B85"; "son" -> "rgba(91,214,138,.16)" to OK
                "fx" -> "rgba(255,180,84,.14)" to WARN; else -> "rgba(154,167,180,.14)" to TEXTE3
            }
            p.R(x + w, y, l, 11.0, 3.0, fond)
            p.T(x + w + l / 2, y + 8.3, texte, 8.0, enc, 'm', mono = true, gras = true)
            w += l + 3
        }
    }

    private fun coupe(t: String, n: Int): String = if (t.length > n) t.take(max(1, n - 1)) + "…" else t

    /** A function or a sound end: a pill (rounded for a function, square for hardware), lit by its state. */
    private fun pastille(p: Pinceau, nd: FonctionsMontage.Noeud, o: Pair<Double, Double>, w: Double, h: Double) {
        val carre = "materiel" in nd.badges
        val tx = "tx" in nd.badges
        val bw = largeurBadges(nd)
        val fond = when (nd.genre) { FonctionsMontage.Genre2.SON -> "#10201A"; FonctionsMontage.Genre2.MATERIEL -> "#161B21"; else -> "#131C24" }
        val r = if (carre) 4.0 else h / 2
        p.groupe {
            translate(o.first, o.second)
            if (nd.statut == Statut.PRET) opacite(.6)
            when (nd.statut) {
                Statut.OK -> {
                    val c = if (tx) "255,90,54" else "63,224,200"
                    for ((e, a) in listOf(6.0 to .1, 3.5 to .18)) R(0.0, 0.0, w, h, r, null, "rgba($c,$a)", e)
                    R(0.0, 0.0, w, h, r, fond, if (tx) TX else RX, 1.8)
                }
                Statut.MANQUE -> R(0.0, 0.0, w, h, r, fond, WARN, 1.2, doubleArrayOf(5.0, 4.0))
                Statut.OCCUPE -> R(0.0, 0.0, w, h, r, "rgba(229,72,77,.12)", DANGER, 2.0)
                Statut.ERREUR -> R(0.0, 0.0, w, h, r, fond, DANGER, 1.2, doubleArrayOf(4.0, 3.0))
                else -> R(0.0, 0.0, w, h, r, fond, "#2F4150", 1.2, if ("fictif" in nd.badges) doubleArrayOf(5.0, 3.0) else null)
            }
            val x = if (carre) 9.0 else 13.0
            T(x, 15.0, coupe(nd.nom, floor((w - 22) / 6.1).toInt()), 11.0, TEXTE, 's', mono = false, gras = true, max = w - x - 8)
            val xb = w - bw - (if (carre) 6 else 12)
            badges(this, nd, xb, h - 16)
            T(x, h - 8, coupe(nd.detail.ifBlank { STATUTS[nd.statut].orEmpty() }, floor((w - bw - 26) / 4.7).toInt()), 9.0, TEXTE2, 's', mono = false, max = xb - x - 3)
        }
    }

    /** A link the way the signal goes: a curve, its kind's colour, an arrow, its words. */
    private fun arc(p: Pinceau, a: FonctionsMontage.Arc, p1: Pair<Double, Double>, p2: Pair<Double, Double>) {
        val dx = max(24.0, abs(p2.first - p1.first) * .45)
        // As the page: out rightwards, in from the left (a link inside one column loops round).
        val dir = 1.0
        val c2x = p2.first - dx * dir
        val d = "M${n(p1.first)} ${n(p1.second)} C${n(p1.first + dx * dir)} ${n(p1.second)} ${n(c2x)} ${n(p2.second)} ${n(p2.first)} ${n(p2.second)}"
        val ok = a.statut == Statut.OK
        val (col, ep, ti) = when (a.type) {
            FonctionsMontage.Type.RF -> Triple(if (ok) RX else "#7D8893", 3.4, null)
            FonctionsMontage.Type.IQ -> Triple(INFO, 2.2, doubleArrayOf(7.0, 4.0))
            FonctionsMontage.Type.AUDIO -> Triple(OK, 2.0, null)
            FonctionsMontage.Type.CAT -> Triple(WARN, 1.6, null)
            FonctionsMontage.Type.RESEAU -> Triple(TEXTE2, 1.8, doubleArrayOf(1.0, 5.0))
            FonctionsMontage.Type.LAN -> Triple("#5AA9FF", 2.2, doubleArrayOf(8.0, 5.0))
        }
        val (cl, ct) = when (a.statut) { Statut.MANQUE -> WARN to doubleArrayOf(4.0, 4.0); Statut.OCCUPE -> DANGER to doubleArrayOf(6.0, 3.0); else -> col to ti }
        p.groupe {
            if (a.statut == Statut.PRET) opacite(.35)
            if (a.detecte) opacite(.55)
            P(d, "none", cl, ep, ct, rond = true)
            if (ok) P(d, "none", "#E8FFFB", 2.4, doubleArrayOf(2.0, 12.0), rond = true)
            // The arrowhead (none on radio): the page's marker, its size by the line's width.
            if (a.type != FonctionsMontage.Type.RF) {
                val son = a.type == FonctionsMontage.Type.AUDIO
                val k = (if (son) 7.0 else 6.0) * ep / 8
                val bx = p2.first - 7 * k * dir; val tx = p2.first + 1 * k * dir
                P("M${n(bx)} ${n(p2.second - 4 * k)} L${n(tx)} ${n(p2.second)} L${n(bx)} ${n(p2.second + 4 * k)} Z", if (son) OK else TEXTE2)
            }
            val mx = (p1.first + p2.first) / 2; val my = (p1.second + p2.second) / 2
            if (a.texte.isNotBlank()) T(mx, my - 4, coupe(a.texte, if (a.type == FonctionsMontage.Type.LAN) 30 else 22), 8.5, TEXTE3, 'm', mono = true)
            if (a.sous.isNotBlank()) T(mx, my + 11, coupe(a.sous, 22), 8.5, "#8FC3FF", 'm', mono = true)
        }
    }
}
