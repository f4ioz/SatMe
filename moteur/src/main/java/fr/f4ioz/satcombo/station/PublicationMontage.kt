/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import fr.f4ioz.satcombo.station.Montage.Chaine
import fr.f4ioz.satcombo.station.Montage.Genre
import fr.f4ioz.satcombo.station.Montage.Sens
import fr.f4ioz.satcombo.station.Montage.Signal
import org.json.JSONArray
import org.json.JSONObject

/**
 * « Description du montage » for a web site (the publication type `montage`):
 * the station's hardware as the « Montage » page draws it, and the paths a
 * signal takes through it — from each antenna to each receiver, from each
 * transmitter to its antenna — so that a page can draw the QO-100 chain
 * (dish → LNB → converter → MiniTiouner / SDR, and the uplink).
 *
 * **Names and characteristics only.** A piece's model, its name, its bands,
 * its options, the cables between; the uplink profile's figures and the
 * converters' oscillators. Never an address, a port, a path, a serial number
 * or any setting beyond these; an owner's name that looks like an address
 * gives way to the catalogue's.
 */
object PublicationMontage {

    /** The uplink profile ([fr.f4ioz.satcombo.domain.ProfilMontee]) as published: its words and its estimates. */
    data class Montee(
        val chaine: String, val antenne: String,
        /** The catalogue model that draws the antenna ("parabole-qo100"); empty for one typed by the owner. */
        val antenneModele: String,
        val diametreCm: Int?, val gainDbi: Double, val ampliW: Double, val pertesDb: Double, val pireMaxDbw: Double?,
    )

    /** A converter of the station's conversion table: [sens] "rx" or "tx", its oscillator and the IF window it gives. */
    data class Conversion(val sens: String, val olHz: Long, val inverse: Boolean, val fiBasHz: Long, val fiHautHz: Long)

    /** One step of a path: the piece, and the cable that reached it from the step before (none for the first). */
    data class Etape(val element: String, val mhz: Int? = null, val cable: Montage.Cable? = null)

    /** A path: [rx] from the antenna to a receiver, else from a transmitter to the antenna; [qo100] at 10 GHz or 2.4 GHz at the antenna. */
    data class Voie(val rx: Boolean, val qo100: Boolean, val etapes: List<Etape>)

    const val VOIES_MAX = 16
    private const val PROFONDEUR_MAX = 12

    /** The genres that end a receive path or start a transmit one. */
    private val RADIOS = setOf(Genre.POSTE, Genre.SDR, Genre.RECEPTEUR_DATV)

    /** The keys [json] may write, each level by itself: what a test checks, what the documentation lists. */
    val CLES = mapOf(
        "" to setOf("plan", "elements", "voies", "montee", "conversions", "t"),
        "element" to setOf("id", "genre", "modele", "nom", "court", "bandesMhz", "options", "references", "diametreCm", "gainDbi"),
        "option" to setOf("nom", "valeur"),
        "reference" to setOf("mhz", "de"),
        "voie" to setOf("sens", "qo100", "etapes"),
        "etape" to setOf("id", "mhz", "cable"),
        "montee" to setOf("chaine", "antenne", "diametreCm", "gainDbi", "ampliW", "pertesDb", "pireMaxDbw"),
        "conversion" to setOf("sens", "olMhz", "inverse", "fiBasMhz", "fiHautMhz"))

    // ------------------------------------------------------------------ the paths

    /** The receive and transmit paths of [c], the QO-100 ones first; at most [VOIES_MAX]. */
    fun voies(c: Chaine): List<Voie> {
        val out = LinkedHashSet<Voie>()
        val radios = c.elements.filter { Montage.modele(it)?.genre in RADIOS }
        for (tx in listOf(false, true)) for (r in radios) {
            val chemins = ArrayList<List<Etape>>()
            suis(c, r.id, null, listOf(Etape(r.id)), tx, chemins)
            for (ch in chemins) {
                // Read from the antenna on reception: the cable of each step moves to the one it leads to.
                val etapes = if (tx) ch else ch.indices.reversed().map { i -> Etape(ch[i].element, ch.getOrNull(i + 1)?.mhz, ch.getOrNull(i + 1)?.cable) }
                val bout = if (tx) etapes.last().mhz else etapes.getOrNull(1)?.mhz
                out += Voie(!tx, bout != null && (bout in 2_300..2_500 || bout in 10_000..10_700), etapes)
            }
        }
        return out.sortedWith(compareBy({ !it.qo100 }, { !it.rx })).take(VOIES_MAX)
    }

    /**
     * From [id] (reached by its port [arrivee]) towards an antenna: on reception back up the radio inputs, on
     * transmission down the outputs; each simple path ending on an antenna added to [sortie].
     */
    private fun suis(c: Chaine, id: String, arrivee: String?, chemin: List<Etape>, tx: Boolean, sortie: MutableList<List<Etape>>) {
        if (chemin.size > PROFONDEUR_MAX || sortie.size >= VOIES_MAX) return
        val e = c.element(id) ?: return
        for (p in Montage.ports(e)) {
            if (p.signal != Signal.RF || p.id == arrivee) continue
            if (tx && !(p.voie.emet && p.sens != Sens.ENTREE)) continue
            if (!tx && !(p.voie.recoit && p.sens != Sens.SORTIE)) continue
            for (l in c.liens) {
                val la = when {
                    l.de.element == id && l.de.port == p.id -> l.vers
                    l.vers.element == id && l.vers.port == p.id -> l.de
                    else -> continue
                }
                if (chemin.any { it.element == la.element }) continue
                val q = Montage.port(c, la) ?: continue
                if (q.signal != Signal.RF) continue
                if (tx && !(q.voie.emet && q.sens != Sens.SORTIE)) continue
                if (!tx && !(q.voie.recoit && q.sens != Sens.ENTREE)) continue
                val suite = chemin + Etape(la.element, ActiviteMontage.bandeDuCable(p, q), l.cable)
                if (c.element(la.element)?.let { Montage.modele(it) }?.genre == Genre.ANTENNE) sortie += suite
                else suis(c, la.element, la.port, suite, tx, sortie)
            }
        }
    }

    // ------------------------------------------------------------------ which plan

    /** The plan to publish: the one with the most QO-100 paths (the owner's on a tie), else the owner's; null when it is empty. */
    fun choisit(p: PlansMontage.Plans): PlansMontage.Plan? {
        val ordre = (listOf(p.choisi) + p.plans).distinct()
        val meilleur = ordre.maxByOrNull { pl -> voies(pl.chaine).count { it.qo100 } }
        val plan = if (meilleur != null && voies(meilleur.chaine).any { it.qo100 }) meilleur else p.choisi
        return plan.takeIf { !it.chaine.vide }
    }

    // ------------------------------------------------------------------ the message

    /** A word an owner typed, kept when it does not look like an address, a link or a serial number. */
    fun propre(s: String, max: Int): String {
        val t = s.filter { it >= ' ' && it != '<' && it != '>' }.trim().take(max)
        return if (SUSPECTS.any { it.containsMatchIn(t) }) "" else t
    }

    private val SUSPECTS = listOf(
        Regex("""\b\d{1,3}(\.\d{1,3}){3}\b"""),                // IPv4
        Regex("""(?i)\b([0-9a-f]{2}[:-]){3,}[0-9a-f]{2}\b"""),  // MAC
        Regex("""[0-9a-fA-F]{0,4}::[0-9a-fA-F]"""),               // IPv6
        Regex("""(?i)://|www\.|@"""),
        Regex("""(?i)\b[a-z0-9-]+\.(fr|com|net|org|local|lan|home|io|de|uk|eu|be|ch|info)\b"""),
        Regex("""(?i)\b(s/?n|serial|série|serie|n° de série|mot de passe|password|passwd|token|jeton)\b"""),
        Regex("""\d{7,}"""),
    )

    private fun r1(x: Double) = Math.round(x * 10) / 10.0
    private fun mhz(hz: Long) = Math.round(hz / 1000.0) / 1000.0

    /**
     * The message for [c] (without its time): the pieces (not the programs), the paths, the uplink profile when
     * the plan draws its antenna, the converters. Null when the chain has nothing to show.
     */
    fun json(nomPlan: String, c: Chaine, montee: Montee? = null, conversions: List<Conversion> = emptyList()): JSONObject? {
        val pieces = c.elements.filter { e -> Montage.modele(e)?.genre.let { it != null && it != Genre.BOITE } }
        if (pieces.isEmpty()) return null
        val voies = voies(c)
        // The uplink profile only when this plan transmits through its antenna (or, for a typed antenna, transmits to QO-100).
        val m = montee?.takeIf { mo ->
            voies.any { v -> !v.rx && v.qo100 && (mo.antenneModele.isEmpty() || c.element(v.etapes.last().element)?.modele == mo.antenneModele) }
        }
        val o = JSONObject()
        propre(nomPlan, 60).takeIf { it.isNotEmpty() }?.let { o.put("plan", it) }
        o.put("elements", JSONArray(pieces.map { e ->
            val mod = Montage.modele(e)!!
            val j = JSONObject().put("id", e.id).put("genre", mod.genre.name.lowercase()).put("modele", mod.id)
                .put("nom", propre(e.nom, 60).ifEmpty { mod.nom }).put("court", CatalogueMateriel.court(mod))
            val ports = Montage.ports(e)
            val bandes = ports.filter { it.signal == Signal.RF }.mapNotNull { it.mhz }.distinct().sorted()
            if (bandes.isNotEmpty()) j.put("bandesMhz", JSONArray(bandes))
            val reglees = mod.reglees(e.options)
            if (mod.options.isNotEmpty()) j.put("options", JSONArray(mod.options.map { op ->
                JSONObject().put("nom", op.nom).put("valeur", op.valeurs.firstOrNull { it.first == reglees[op.id] }?.second ?: reglees[op.id].orEmpty())
            }))
            // The frequency references reaching it (a GPSDO's 10 MHz, a 25 MHz into the LNB).
            val refs = ports.filter { it.signal == Signal.REF && it.sens == Sens.ENTREE }.mapNotNull { p ->
                val l = c.liens.firstOrNull { (it.de.element == e.id && it.de.port == p.id) || (it.vers.element == e.id && it.vers.port == p.id) } ?: return@mapNotNull null
                val autre = if (l.de.element == e.id && l.de.port == p.id) l.vers else l.de
                if (pieces.none { it.id == autre.element }) return@mapNotNull null
                JSONObject().also { r -> (Montage.port(c, autre)?.mhz ?: p.mhz)?.let { r.put("mhz", it) } }.put("de", autre.element)
            }
            if (refs.isNotEmpty()) j.put("references", JSONArray(refs))
            when {
                e.modele == "parabole-35" -> j.put("diametreCm", 35)
                m != null && m.antenneModele == e.modele && m.diametreCm != null -> j.put("diametreCm", m.diametreCm)
            }
            if (m != null && m.antenneModele == e.modele && m.antenneModele.isNotEmpty()) j.put("gainDbi", r1(m.gainDbi))
            j
        }))
        o.put("voies", JSONArray(voies.map { v ->
            JSONObject().put("sens", if (v.rx) "rx" else "tx").put("qo100", v.qo100).put("etapes", JSONArray(v.etapes.map { s ->
                JSONObject().put("id", s.element).also { x -> s.mhz?.let { x.put("mhz", it) }; s.cable?.let { x.put("cable", it.nom) } }
            }))
        }))
        m?.let { mo ->
            o.put("montee", JSONObject().put("chaine", mo.chaine.take(60)).put("antenne", mo.antenne.take(60))
                .also { x -> mo.diametreCm?.let { x.put("diametreCm", it) } }
                .put("gainDbi", r1(mo.gainDbi)).put("ampliW", r1(mo.ampliW)).put("pertesDb", r1(mo.pertesDb))
                .also { x -> mo.pireMaxDbw?.let { x.put("pireMaxDbw", r1(it)) } })
        }
        val conv = conversions.filter { it.olHz > 0 && (it.sens == "rx" || it.sens == "tx") }.distinctBy { it.sens }
        if (conv.isNotEmpty()) o.put("conversions", JSONArray(conv.map { k ->
            JSONObject().put("sens", k.sens).put("olMhz", mhz(k.olHz)).put("inverse", k.inverse).put("fiBasMhz", mhz(k.fiBasHz)).put("fiHautMhz", mhz(k.fiHautHz))
        }))
        return o
    }
}
