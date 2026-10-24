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
 * What the station does now, put on the chain: the hardware that receives,
 * the hardware that transmits, the cables the signal takes, the frequencies
 * on them. Read from what SatMe's page already reads (`api/sdr`,
 * `api/datv`, `api/datv/emission`…): the same JSON on the PC and the phone.
 *
 * **Display only**: nothing here starts, stops or changes anything.
 */
object ActiviteMontage {

    /** SatMe's state, as its routes give it; null when that part is absent. */
    class Lectures(
        val etat: JSONObject? = null, val sdr: JSONObject? = null, val datv: JSONObject? = null,
        val emissionDatv: JSONObject? = null, val emissionSstv: JSONObject? = null, val iq: JSONObject? = null,
        val qo100: JSONObject? = null, val lien: JSONObject? = null,
        /** The programs and services beside the computer, as the PC sees them (see [boites]). */
        val boites: JSONObject? = null,
        /** The Icom over the network (`api/icomreseau`): its link, its state, its delay. */
        val icom: JSONObject? = null,
        /** The USB knob (`api/molette`). */
        val molette: JSONObject? = null,
    )

    /** A boîte's state: talking with SatMe now, set up but quiet, or off (not set up). */
    enum class EtatBoite { CONNECTE, CONFIGURE, ARRET }
    data class Boite(val etat: EtatBoite, val texte: String)

    /**
     * The roles active now ("sdr:pluto", "tx:bladerf", "cat"…), the ones in
     * error, the frequencies (MHz) by path: "ciel-rx" the downlink, "fi-rx"
     * the IF after the LNB, "ciel-tx" the uplink, "fi-tx" before the
     * up-converter. [resume]: the sentences shown above the drawing.
     */
    data class Activite(
        val rx: Set<String> = emptySet(), val tx: Set<String> = emptySet(), val relies: Set<String> = emptySet(),
        val erreurs: Map<String, String> = emptyMap(), val mhz: Map<String, Double> = emptyMap(), val resume: List<String> = emptyList(),
        /** The boîtes' states, by their role's name ("wavelog", "lien"…). */
        val boites: Map<String, Boite> = emptyMap(),
        /** A word under a piece, by role ("cat" → « réseau · 12 ms »). */
        val notes: Map<String, String> = emptyMap(),
        /** The way a role reaches the computer when it is known: "reseau" (its LAN or Wi-Fi port), "usb". */
        val liaisons: Map<String, String> = emptyMap(),
    ) {
        val vide get() = rx.isEmpty() && tx.isEmpty()
    }

    enum class Etat { REPOS, RELIE, RX, TX, RXTX }

    /** A cable seen live but not drawn: from [de] to [vers], the [cable] to add, [texte] what it carries. */
    data class Detecte(val de: Montage.Bout, val vers: Montage.Bout, val cable: Montage.Cable, val texte: String) {
        fun json(): JSONObject = JSONObject().put("de", de.toString()).put("vers", vers.toString()).put("cable", cable.name).put("texte", texte)
    }

    /** The chain lit: each element's and cable's state, the frequency on each radio cable lit, the elements in error. */
    data class Allumage(val elements: Map<String, Etat>, val liens: Map<String, Etat>, val mhz: Map<String, Double>, val erreurs: Map<String, String>,
                        val boites: Map<String, Boite> = emptyMap(), val notes: Map<String, String> = emptyMap(),
                        /** The links the station uses but the drawing lacks (an Icom over its LAN, no LAN cable drawn). */
                        val detectes: List<Detecte> = emptyList()) {
        /** The elements taking part in what happens now (the simplified drawing). */
        fun enOeuvre(): Set<String> = elements.filterValues { it != Etat.REPOS }.keys
    }

    private fun JSONObject?.b(k: String) = this?.optBoolean(k, false) == true
    private fun JSONObject?.s(k: String) = this?.optString(k, "").orEmpty()
    private fun JSONObject?.d(k: String) = this?.optDouble(k, Double.NaN) ?: Double.NaN

    /** The activity read from SatMe's routes. */
    fun depuis(l: Lectures): Activite {
        val rx = LinkedHashSet<String>(); val tx = LinkedHashSet<String>(); val relies = LinkedHashSet<String>()
        val err = LinkedHashMap<String, String>(); val mhz = LinkedHashMap<String, Double>(); val resume = ArrayList<String>()
        fun f3(m: Double) = String.format(java.util.Locale.FRANCE, "%.3f", m)
        // The QO-100 cockpit's frequencies: the base, what a receiver or a transmitter says comes over them.
        val q = l.qo100
        val conv = l.etat?.optJSONObject("convertisseurs")
        val desc = q.d("descente"); val mont = q.d("montee")
        if (desc > 1e9) {
            mhz["ciel-rx"] = desc / 1e6
            val ol = conv.d("rxOl"); if (ol > 0) mhz["fi-rx"] = kotlin.math.abs(desc - ol) / 1e6
        }
        if (mont > 1e9) {
            mhz["ciel-tx"] = mont / 1e6
            val ol = conv.d("txOl"); if (ol > 0) mhz["fi-tx"] = kotlin.math.abs(mont - ol) / 1e6
        }
        val s = l.sdr
        if (s != null && !s.b("absent")) {
            val r = s.s("recepteur").ifBlank { "rtl" }
            if (s.b("actif")) {
                rx += "sdr:$r"
                val ciel = s.d("ciel")
                if (ciel > 1e6) mhz["ciel-rx"] = ciel / 1e6
                resume += "Réception SDR : " + nomRecepteur(r) + (if (ciel > 1e6) ", " + f3(ciel / 1e6) + " MHz" else "") +
                    (if (r == "fichier" || r == "generateur") " (aucun matériel)" else "")
            }
            if (s.s("erreur").isNotBlank()) err["sdr:$r"] = s.s("erreur")
        }
        val d = l.datv
        if (d != null && d.optBoolean("ok", true)) {
            val r = d.s("recepteur").ifBlank { "minitiouner" }
            if (d.b("actif")) {
                rx += "datv:$r"
                if (d.s("noeud").isNotBlank()) rx += "noeud"
                val ciel = d.d("ciel"); val fi = d.d("fi")
                if (ciel > 3e6) mhz["ciel-rx"] = ciel / 1e3
                if (fi > 0) mhz["fi-rx"] = fi / 1e3
                resume += "Réception DATV : " + nomRecepteur(r) + (if (d.b("verrouille")) ", accrochée" else ", recherche") +
                    (if (d.s("noeud").isNotBlank()) " (par SatMe-Node)" else "")
            }
            if (d.s("erreur").isNotBlank()) err["datv:$r"] = d.s("erreur")
        }
        val e = l.emissionDatv
        if (e != null && e.b("emet")) {
            val t = e.s("emetteurEnCours").ifBlank { e.s("emetteur") }.ifBlank { "pluto" }
            tx += "tx:$t"
            val k = e.d("khzEnCours").takeIf { it > 0 } ?: e.d("khz")
            if (k > 0) mhz["ciel-tx"] = k / 1e3
            resume += "Émission DATV : " + nomRecepteur(t) + (if (k > 0) ", " + f3(k / 1e3) + " MHz" else "") +
                (if (e.b("charge")) " (sur charge)" else if (e.b("interieur")) " (essai en intérieur)" else "")
        }
        if (e != null && e.s("erreur").isNotBlank()) err["tx:" + e.s("emetteur").ifBlank { "pluto" }] = e.s("erreur")
        val x = l.emissionSstv
        if (x != null && x.b("emet")) {
            val ec = x.optJSONObject("enCours")
            val t = ec.s("emetteur").ifBlank { x.s("emetteur") }.ifBlank { "pluto" }
            tx += "tx:$t"
            val hz = ec.d("hz")
            if (hz > 0) mhz["ciel-tx"] = hz / 1e6
            resume += "Émission SSTV : " + nomRecepteur(t) + (if (hz > 0) ", " + f3(hz / 1e6) + " MHz" else "")
        }
        val iq = l.iq
        if (iq != null && iq.b("actif") && iq.s("appareil").isNotBlank()) {
            tx += "tx:" + iq.s("appareil")
            resume += "Calibration I/Q : " + nomRecepteur(iq.s("appareil"))
        }
        val notes = LinkedHashMap<String, String>(); val liaisons = LinkedHashMap<String, String>()
        val cat = l.etat?.optJSONObject("cat")
        // The Icom over the network: its LAN cable lit, not its USB; its state and delay under it.
        val ic = l.icom
        val parReseau = ic != null && ic.s("liaison") == "RESEAU"
        if (parReseau) {
            liaisons["cat"] = "reseau"
            val sert = ic.b("cat") || ic.b("audio")
            val quoi = listOfNotNull("CAT".takeIf { ic.b("cat") }, "son".takeIf { ic.b("audio") }).joinToString(" + ")
            notes["cat"] = when (ic.s("etat")) {
                "CONNECTE" -> "réseau · " + ic.optInt("latenceMs", 0) + " ms" + (if (quoi.isNotBlank()) " · $quoi" else "")
                "RECHERCHE", "CONNEXION" -> "réseau : connexion…"
                "REFUSE" -> "réseau : refusé"
                "PERDU" -> "réseau : perdu"
                else -> "réseau (arrêté)"
            }
            if (sert && ic.s("etat") in setOf("PERDU", "REFUSE")) err["cat"] = "liaison réseau " + (if (ic.s("etat") == "REFUSE") "refusée" else "perdue") +
                (ic.s("message").takeIf { it.isNotBlank() }?.let { " : $it" } ?: "")
        }
        if (cat.b("connecte")) {
            rx += "cat"
            if (!parReseau && !cat.b("simule")) liaisons["cat"] = "usb"
            val dl = cat.d("dl")
            if (dl > 1e6 && "ciel-rx" !in mhz) mhz["ciel-rx"] = dl / 1e6
            resume += "Poste en CAT : " + cat.s("modele") + (if (cat.b("simule")) " (simulé)" else if (parReseau) " (par le réseau)" else "")
        }
        // The USB knob: read, never written to.
        val mo = l.molette
        if (mo.b("on")) {
            if (mo.s("etat") == "ouvert") { relies += "molette"; notes["molette"] = mo.s("puce").ifBlank { "en service" } }
            else notes["molette"] = mo.s("message").ifBlank { "absente" }.take(40)
        }
        val rot = l.etat?.optJSONObject("rotor")
        if (rot.b("connecte")) relies += "rotor"
        val li = l.lien
        if (li.b("ecoute") && (li?.optInt("clients", 0) ?: 0) > 0) relies += "telephone"
        val bx = boites(l.boites)
        bx.forEach { (k, v) -> if (v.etat == EtatBoite.CONNECTE) relies += "boite:$k" }
        return Activite(rx, tx, relies, err, mhz, resume, bx, notes, liaisons)
    }

    /**
     * The activity read from SatMe's routes and the parts' state [i]: the
     * sound decoders (SSTV, APRS, FT8, CW, phonie) fed by a rig's own sound
     * (its USB codec, its sound over the network) light that rig, its antenna
     * and the computer, CAT or not; by another card, that card. The rig's
     * frequency ("poste-rx"): its CAT's, else the followed satellite's.
     */
    fun depuis(l: Lectures, i: FonctionsMontage.Instantane?): Activite {
        val a = depuis(l)
        if (i == null || !i.sonActif || i.sonParSdr || i.sonAppareil.isBlank()) return a
        val ecoutent = FonctionsMontage.ecoutentEntree(i)
        if (ecoutent.isEmpty()) return a
        val quoi = ecoutent.map { when (it) { "sstv-rx" -> "SSTV"; "ft8" -> i.ft8Mode.ifBlank { "FT8" }; "cw" -> "CW"; "aprs" -> "APRS"; else -> "phonie" } }.joinToString(", ")
        val rx = LinkedHashSet(a.rx); val mhz = LinkedHashMap(a.mhz); val resume = ArrayList(a.resume)
        val notes = LinkedHashMap(a.notes); val liaisons = LinkedHashMap(a.liaisons)
        fun f3(m: Double) = String.format(java.util.Locale.FRANCE, "%.3f", m)
        if (i.estSonDuPoste(i.sonAppareil)) {
            rx += "cat"
            val reseau = i.sonAppareil == FonctionsMontage.SOURCE_RESEAU
            if ("cat" !in liaisons) liaisons["cat"] = if (reseau) "reseau" else "usb"
            val cat = l.etat?.optJSONObject("cat")
            val dl = cat.d("dl").takeIf { cat.b("connecte") && it > 1e6 }
            val sat = l.etat?.optJSONObject("ciel").d("dl").takeIf { l.etat?.has("suivi") == true && it > 1e6 }
            val f = (dl ?: sat)?.div(1e6)
            if (f != null) { mhz["poste-rx"] = f; if ("ciel-rx" !in mhz) mhz["ciel-rx"] = f }
            val nom = i.libelles[i.sonAppareil]?.substringBefore(" (")?.takeIf { it.isNotBlank() && !it.contains('[') }
                ?: nomPoste(i.catModele.ifBlank { cat.s("modele") })
            resume += quoi + " : " + nom + (if (reseau) " par le réseau (son)" else " par sa carte son USB") +
                (if (f != null) ", " + f3(f) + " MHz" else "") +
                (if (!cat.b("connecte")) (if (sat != null) " (sans CAT : fréquence du satellite suivi)" else " (sans CAT)") else "")
            if ("cat" !in notes) notes["cat"] = (if (reseau) "son réseau → " else "son USB → ") + quoi
        } else {
            rx += "son"
            resume += quoi + " : carte son « " + i.nomCarte(i.sonAppareil) + " »"
        }
        return a.copy(rx = rx, mhz = mhz, resume = resume, notes = notes, liaisons = liaisons)
    }

    private fun nomPoste(m: String) = when (m) { "IC9700", "SIM" -> "IC-9700"; "TS2000", "TS2000SIM" -> "TS-2000"; "PAIRE" -> "IC-705"; "" -> "le poste"; else -> m }

    /**
     * The boîtes' states from what the PC knows (settings filled in, a
     * service on, programs connected): never asked of the services
     * themselves. Keys of [o]: wavelog {url, cle}, qrz {configure}, psk {on,
     * etat}, chat {on, etat}, carnets (the rigctld state: on, clients, port),
     * lien (on, clients, port), telephone {connecte, adresse}, noeud {nom,
     * actif}, ecoute {on}.
     */
    fun boites(o: JSONObject?): Map<String, Boite> {
        if (o == null) return emptyMap()
        val r = LinkedHashMap<String, Boite>()
        fun n(x: JSONObject?) = x?.optInt("clients", 0) ?: 0
        o.optJSONObject("wavelog")?.let { w ->
            r["wavelog"] = when {
                w.b("url") && w.b("cle") -> Boite(EtatBoite.CONFIGURE, "adresse et clé réglées")
                w.b("url") -> Boite(EtatBoite.CONFIGURE, "adresse réglée, sans clé")
                else -> Boite(EtatBoite.ARRET, "pas réglé")
            }
        }
        o.optJSONObject("qrz")?.let { q -> r["qrz"] = if (q.b("configure")) Boite(EtatBoite.CONFIGURE, "identifiant réglé") else Boite(EtatBoite.ARRET, "pas réglé") }
        o.optJSONObject("psk")?.let { p -> r["psk"] = if (p.b("on")) Boite(EtatBoite.CONNECTE, p.s("etat").ifBlank { "envoi choisi" }) else Boite(EtatBoite.ARRET, "envoi non choisi") }
        o.optJSONObject("chat")?.let { c -> r["chat"] = if (c.b("on")) Boite(EtatBoite.CONNECTE, c.s("etat").ifBlank { "en marche" }) else Boite(EtatBoite.ARRET, "arrêté") }
        o.optJSONObject("carnets")?.let { k ->
            r["carnets"] = when {
                !k.b("on") -> Boite(EtatBoite.ARRET, "arrêté")
                n(k) > 0 -> Boite(EtatBoite.CONNECTE, n(k).toString() + (if (n(k) > 1) " programmes" else " programme"))
                else -> Boite(EtatBoite.CONFIGURE, "en attente" + (k.optInt("port", 0).takeIf { it > 0 }?.let { " (port $it)" } ?: ""))
            }
        }
        o.optJSONObject("lien")?.let { k ->
            r["lien"] = when {
                !k.b("on") -> Boite(EtatBoite.ARRET, "arrêté")
                n(k) > 0 -> Boite(EtatBoite.CONNECTE, n(k).toString() + (if (n(k) > 1) " clients" else " client"))
                else -> Boite(EtatBoite.CONFIGURE, "en attente" + (k.optInt("port", 0).takeIf { it > 0 }?.let { " (port $it)" } ?: ""))
            }
        }
        o.optJSONObject("telephone")?.let { t ->
            r["telephone"] = if (t.b("connecte")) Boite(EtatBoite.CONNECTE, t.s("adresse").ifBlank { "appairé" }) else Boite(EtatBoite.ARRET, "pas appairé")
        }
        o.optJSONObject("noeud")?.let { k ->
            r["noeud"] = when {
                k.s("nom").isBlank() -> Boite(EtatBoite.ARRET, "aucun")
                k.b("actif") -> Boite(EtatBoite.CONNECTE, k.s("nom"))
                else -> Boite(EtatBoite.CONFIGURE, k.s("nom"))
            }
        }
        o.optJSONObject("ecoute")?.let { k ->
            r["ecoute"] = when {
                n(k) > 0 -> Boite(EtatBoite.CONNECTE, n(k).toString() + (if (n(k) > 1) " auditeurs" else " auditeur"))
                k.b("on") -> Boite(EtatBoite.CONNECTE, "le son part vers une page")
                else -> Boite(EtatBoite.ARRET, "personne n'écoute")
            }
        }
        o.optJSONObject("espace")?.let { k ->
            r["espace"] = when {
                !k.b("on") -> Boite(EtatBoite.ARRET, "pas consultée")
                k.s("erreur").isNotBlank() -> Boite(EtatBoite.CONFIGURE, k.s("erreur").take(30))
                else -> Boite(EtatBoite.CONNECTE, k.s("texte").ifBlank { "à jour" })
            }
        }
        return r
    }

    private fun nomRecepteur(r: String) = when (r) {
        "pluto" -> "ADALM-PLUTO"; "bladerf" -> "bladeRF"; "hackrf" -> "HackRF"; "minitiouner" -> "MiniTiouner"; "rtl" -> "clé RTL-SDR"
        "fichier" -> "enregistrement I/Q"; "generateur" -> "générateur d'essai"; else -> r
    }

    /**
     * The chain lit by [a]: from each piece of hardware receiving, back up
     * its radio inputs to the antenna; from each transmitting, down its radio
     * outputs to the antenna (or the dummy load); from both, along the data
     * cables to the computer.
     */
    fun allume(c: Chaine, a: Activite): Allumage {
        val el = HashMap<String, Etat>(); val li = HashMap<String, Etat>(); val mhz = HashMap<String, Double>()
        fun marque(m: MutableMap<String, Etat>, id: String, e: Etat) {
            val v = m[id] ?: Etat.REPOS
            m[id] = when {
                v == e || v == Etat.RXTX -> v
                (v == Etat.RX && e == Etat.TX) || (v == Etat.TX && e == Etat.RX) -> Etat.RXTX
                e == Etat.RELIE -> if (v == Etat.REPOS) Etat.RELIE else v
                else -> e
            }
        }
        val roles = c.elements.associate { it.id to (Montage.modele(it)?.roles ?: emptySet()) }
        fun sources(r: Set<String>) = c.elements.filter { e -> roles[e.id]!!.any { it in r } }.map { it.id }

        // Radio: along the ports of the path, never back through the port it came by.
        fun suis(depart: String, etat: Etat) {
            val tx = etat == Etat.TX
            val vus = HashSet<String>()
            val file = ArrayDeque<Pair<String, String?>>()
            file.add(depart to null)
            while (file.isNotEmpty()) {
                val (id, arrivee) = file.removeFirst()
                if (!vus.add(id + "/" + arrivee)) continue
                val e = c.element(id) ?: continue
                val bande = if (id == depart) bandePoste(e, if (tx) null else a.mhz["poste-rx"]) else null
                for (p in Montage.ports(e)) {
                    if (p.signal != Signal.RF || p.id == arrivee) continue
                    if (bande != null && p.id !in bande) continue
                    if (tx && !(p.voie.emet && p.sens != Sens.ENTREE)) continue
                    if (!tx && !(p.voie.recoit && p.sens != Sens.SORTIE)) continue
                    for (l in c.liens) {
                        val (ici, la) = when {
                            l.de.element == id && l.de.port == p.id -> l.de to l.vers
                            l.vers.element == id && l.vers.port == p.id -> l.vers to l.de
                            else -> continue
                        }
                        val loin = Montage.port(c, la) ?: continue
                        if (loin.signal != Signal.RF || (if (tx) !loin.voie.emet else !loin.voie.recoit)) continue
                        marque(li, l.id, etat); marque(el, la.element, etat)
                        // The band the cable carries: the output's (a wide-band input takes what comes), else the higher.
                        val nominal = bandeDuCable(Montage.port(c, ici), loin)
                        // The frequency shown: the sky's or the IF's, whichever belongs to the cable's band.
                        val vues = (if (tx) listOf("ciel-tx", "fi-tx") else listOf("ciel-rx", "fi-rx", "poste-rx")).mapNotNull { a.mhz[it] }.distinct()
                        val f = if (nominal == null) vues.firstOrNull()
                            else vues.filter { kotlin.math.abs(it - nominal) / nominal < 0.15 }.minByOrNull { kotlin.math.abs(it - nominal) }
                        f?.let { mhz[l.id] = it }
                        file.add(la.element to la.port)
                    }
                }
            }
        }
        // How each piece reaches the computer when the station says it (an Icom over its LAN, not its USB).
        fun liaison(id: String): String? = roles[id]!!.firstNotNullOfOrNull { a.liaisons[it] }
        // Data: from the radio to the computer, through the boxes between.
        fun donnees(depart: String, etat: Etat) {
            val vus = HashSet<String>(); val file = ArrayDeque(listOf(depart))
            while (file.isNotEmpty()) {
                val id = file.removeFirst()
                if (!vus.add(id)) continue
                val g = c.element(id)?.let { Montage.modele(it) }?.genre
                if (id != depart && (g == Genre.ORDINATEUR || g == Genre.TELEPHONE)) continue
                for (l in c.liens) {
                    val autre = when (id) { l.de.element -> l.vers; l.vers.element -> l.de; else -> continue }
                    val ici = if (autre == l.vers) l.de else l.vers
                    val p = Montage.port(c, ici) ?: continue
                    if (!p.signal.versPc) continue
                    if (id == depart) when (liaison(depart)) {
                        "reseau" -> if (p.connecteur != "RJ45" && p.connecteur != "Wi-Fi") continue
                        "usb" -> if (p.connecteur == "RJ45" || p.connecteur == "Wi-Fi") continue
                    }
                    val ga = c.element(autre.element)?.let { Montage.modele(it) }?.genre
                    // Towards the computer only: a radio or an antenna beyond is not part of it.
                    if (ga !in setOf(Genre.ORDINATEUR, Genre.TELEPHONE, Genre.NOEUD, Genre.INTERFACE, Genre.CARTE_SON)) continue
                    marque(li, l.id, etat); marque(el, autre.element, etat)
                    file.add(autre.element)
                }
            }
        }
        // A piece reaching the computer over the network with no LAN or Wi-Fi cable drawn: the cable seen, offered.
        val detectes = ArrayList<Detecte>()
        val ordinateur = c.elements.firstOrNull { Montage.modele(it)?.genre == Genre.ORDINATEUR }
        fun detecte(depart: String) {
            if (liaison(depart) != "reseau" || ordinateur == null) return
            val e = c.element(depart) ?: return
            val reseau = Montage.ports(e).filter { it.signal == Signal.DONNEES && (it.connecteur == "RJ45" || it.connecteur == "Wi-Fi") }
            val dessine = c.liens.any { l -> reseau.any { p -> (l.de.element == depart && l.de.port == p.id) || (l.vers.element == depart && l.vers.port == p.id) } }
            if (dessine) return
            for (p in reseau) {
                val q = Montage.ports(ordinateur).firstOrNull { it.connecteur == p.connecteur && it.signal == Signal.DONNEES } ?: continue
                val k = if (p.connecteur == "Wi-Fi") Montage.Cable.WIFI else Montage.Cable.ETHERNET
                if (Montage.refus(c, Montage.Bout(depart, p.id), Montage.Bout(ordinateur.id, q.id), k) != null) continue
                detectes += Detecte(Montage.Bout(depart, p.id), Montage.Bout(ordinateur.id, q.id), k,
                    roles[depart]!!.firstNotNullOfOrNull { a.notes[it] } ?: "réseau")
                marque(el, ordinateur.id, Etat.RELIE)
                return
            }
        }
        for (id in sources(a.rx)) { marque(el, id, Etat.RX); suis(id, Etat.RX); donnees(id, Etat.RX); detecte(id) }
        for (id in sources(a.tx)) { marque(el, id, Etat.TX); suis(id, Etat.TX); donnees(id, Etat.TX) }
        for (id in sources(a.relies)) {
            marque(el, id, Etat.RELIE)
            for (l in c.liens) {
                val autre = when (id) { l.de.element -> l.vers; l.vers.element -> l.de; else -> continue }
                val p = Montage.port(c, autre) ?: continue
                if (!p.signal.versPc) continue
                marque(li, l.id, Etat.RELIE); marque(el, autre.element, Etat.RELIE)
            }
        }
        // SatMe runs on the computer: always part of what happens (the simplified drawing keeps it).
        if (ordinateur != null && el.values.any { it != Etat.REPOS }) marque(el, ordinateur.id, Etat.RELIE)
        val erreurs = HashMap<String, String>()
        for ((role, texte) in a.erreurs) sources(setOf(role)).forEach { erreurs[it] = texte }
        c.elements.forEach { if (it.id !in el) el[it.id] = Etat.REPOS }
        c.liens.forEach { if (it.id !in li) li[it.id] = Etat.REPOS }
        val bx = HashMap<String, Boite>()
        for (e in c.elements) {
            val r = roles[e.id]!!.firstOrNull { it.startsWith("boite:") } ?: continue
            bx[e.id] = a.boites[r.removePrefix("boite:")] ?: Boite(EtatBoite.ARRET, "état inconnu ici")
        }
        val notes = HashMap<String, String>()
        for (e in c.elements) roles[e.id]!!.firstNotNullOfOrNull { a.notes[it] }?.let { notes[e.id] = it }
        return Allumage(el, li, mhz, erreurs, bx, notes, detectes)
    }

    /** A rig's antenna ports of the band it listens on ([mhz]); null (all of them) when unknown or none fits. */
    private fun bandePoste(e: Montage.Element, mhz: Double?): Set<String>? {
        if (mhz == null || Montage.modele(e)?.genre != Genre.POSTE) return null
        val l = Montage.ports(e).filter { it.signal == Signal.RF && it.mhz != null && kotlin.math.abs(mhz - it.mhz) / it.mhz < 0.15 }
        return l.map { it.id }.toSet().takeIf { it.isNotEmpty() }
    }

    /** The band a radio cable between [a] and [b] carries (MHz): the output's when one end is an output, else the higher. */
    fun bandeDuCable(a: Montage.Port?, b: Montage.Port?): Int? {
        val s = listOfNotNull(a, b).filter { it.sens == Sens.SORTIE && it.mhz != null }
        if (s.size == 1) return s.single().mhz
        return listOfNotNull(a?.mhz, b?.mhz).maxOrNull()
    }

    fun json(a: Activite, al: Allumage): JSONObject = JSONObject()
        .put("elements", JSONObject(al.elements.mapValues { it.value.name.lowercase() }))
        .put("liens", JSONObject(al.liens.mapValues { it.value.name.lowercase() }))
        .put("mhz", JSONObject(al.mhz))
        .put("erreurs", JSONObject(al.erreurs))
        .put("frequences", JSONObject(a.mhz))
        .put("boites", JSONObject().also { o -> al.boites.forEach { (id, b) -> o.put(id, JSONObject().put("etat", b.etat.name.lowercase()).put("texte", b.texte)) } })
        .put("notes", JSONObject(al.notes))
        .put("detectes", JSONArray(al.detectes.map { it.json() }))
        .put("resume", JSONArray(a.resume))
        .put("rx", a.rx.isNotEmpty()).put("tx", a.tx.isNotEmpty())

    /** A pretend activity, for the page's demonstration (`?essai=`): never what the station does. */
    fun essai(quoi: String): Activite = when (quoi) {
        "rx" -> Activite(rx = setOf("sdr:pluto", "sdr:rtl", "cat", "datv:minitiouner"), relies = setOf("boite:lien", "boite:carnets"),
            mhz = mapOf("ciel-rx" to 10489.750, "fi-rx" to 739.750),
            resume = listOf("Démonstration : réception (rien n'est reçu)"), boites = BOITES_ESSAI,
            notes = mapOf("cat" to "réseau · 12 ms · CAT + son"), liaisons = mapOf("cat" to "reseau"))
        "tx" -> Activite(rx = setOf("sdr:pluto", "datv:minitiouner", "cat", "noeud"), tx = setOf("tx:pluto", "tx:bladerf", "cat"),
            mhz = mapOf("ciel-rx" to 10489.750, "fi-rx" to 739.750, "ciel-tx" to 2400.250, "fi-tx" to 144.250),
            resume = listOf("Démonstration : émission simulée (rien n'est émis)"), boites = BOITES_ESSAI)
        "datv-pluto" -> Activite(rx = setOf("datv:pluto", "cat"), mhz = mapOf("ciel-rx" to 10491.500, "fi-rx" to 741.500),
            resume = listOf("Démonstration : DATV reçue par l'ADALM-PLUTO (rien n'est reçu)"))
        "sstv-carte" -> Activite(rx = setOf("cat", "son"), mhz = mapOf("ciel-rx" to 10489.800),
            resume = listOf("Démonstration : SSTV par l'IC-9700 et sa carte son (rien n'est reçu)"))
        "sstv-reseau" -> Activite(rx = setOf("cat", "son"), mhz = mapOf("ciel-rx" to 437.550),
            resume = listOf("Démonstration : SSTV par l'IC-9700 sur le réseau (rien n'est reçu)"),
            notes = mapOf("cat" to "réseau · 12 ms · CAT + son"), liaisons = mapOf("cat" to "reseau"))
        else -> Activite()
    }

    /** The boîtes of the demonstration: said so, nothing is asked of any service. */
    private val BOITES_ESSAI = mapOf(
        "wavelog" to Boite(EtatBoite.CONFIGURE, "adresse et clé réglées"), "qrz" to Boite(EtatBoite.CONFIGURE, "identifiant réglé"),
        "psk" to Boite(EtatBoite.ARRET, "envoi non choisi"), "chat" to Boite(EtatBoite.ARRET, "arrêté"),
        "carnets" to Boite(EtatBoite.CONNECTE, "1 programme"), "lien" to Boite(EtatBoite.CONNECTE, "2 clients"),
        "telephone" to Boite(EtatBoite.ARRET, "pas appairé"), "noeud" to Boite(EtatBoite.ARRET, "aucun"),
        "ecoute" to Boite(EtatBoite.ARRET, "personne n'écoute"))

    // ------------------------------------------------------------------ the chain SatMe suggests

    /**
     * A chain made from what SatMe knows of the station: the rig set in
     * Réglages, the rotor, the SDR board, the DATV receiver and transmitter,
     * the converters' oscillators. Only a proposal: the owner takes it or not.
     * [pourquoi]: what each piece was guessed from.
     */
    data class Suggestion(val chaine: Chaine, val pourquoi: List<String>)

    fun suggere(l: Lectures): Suggestion {
        val b = Montage.Bati("Proposée par SatMe")
        val pourquoi = ArrayList<String>()
        val conv = l.etat?.optJSONObject("convertisseurs")
        val cat = l.etat?.optJSONObject("cat")
        val sdr = l.sdr?.takeIf { !it.b("absent") }
        val datv = l.datv?.takeIf { it.optBoolean("ok", true) }
        val dtx = l.emissionDatv?.takeIf { it.optBoolean("ok", true) }

        // The radios.
        val poste = when (cat.s("modele")) {
            "IC9700", "SIM" -> "ic9700"; "TS2000", "TS2000SIM" -> "ts2000"; "PAIRE" -> "ic705"; "" -> null; else -> "poste"
        }?.let { m ->
            pourquoi += "Poste : " + cat.s("modele") + " choisi dans Réglages › Poste"
            b.ajoute(m, if (m == "poste") cat.s("modele") else "")
        }
        val sdrs = LinkedHashMap<String, String>()
        fun sdr(cle: String, raison: String) {
            if (cle in sdrs) return
            val m = when (cle) { "pluto" -> "pluto"; "bladerf" -> "bladerf"; "hackrf" -> "hackrf"; "rtl" -> "rtl-sdr"; else -> return }
            sdrs[cle] = b.ajoute(m); pourquoi += raison
        }
        sdr?.s("recepteur")?.takeIf { it.isNotBlank() && (it != "rtl" || sdr.b("actif") || sdr.s("tuner").isNotBlank()) }
            ?.let { sdr(it, "Récepteur de l'onglet SDR : " + nomRecepteur(it)) }
        dtx?.s("emetteur")?.takeIf { it.isNotBlank() }?.let { sdr(it, "Émetteur DATV choisi : " + nomRecepteur(it)) }
        datv?.s("recepteur")?.takeIf { it == "pluto" || it == "bladerf" }?.let { sdr(it, "Récepteur DATV : " + nomRecepteur(it)) }
        if (dtx?.optJSONObject("bladerf").let { it.b("lu") || it.s("serie").isNotBlank() }) sdr("bladerf", "bladeRF détecté")
        if ((dtx?.optJSONObject("hackrf")?.optJSONArray("cartes")?.length() ?: 0) > 0) sdr("hackrf", "HackRF détecté")
        l.iq?.optJSONArray("appareils")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { o -> if (o.s("serie").isNotBlank()) sdr(o.s("cle"), "Calibration I/Q faite : " + o.s("nom")) }
        }
        val nm = datv?.optJSONObject("noeudMateriel")
        val mini = if (datv?.s("recepteur") == "minitiouner" || nm.s("datv") == "minitiouner") b.ajoute("minitiouner").also { pourquoi += "Récepteur DATV : MiniTiouner" } else null
        val noeud = if ((mini != null || nm != null) && datv.s("noeud").isNotBlank()) b.ajoute("satme-node").also {
            pourquoi += (if (mini != null) "MiniTiouner sur un SatMe-Node (" else "SatMe-Node associé (") + datv.s("noeud") + ")" } else null
        // The node's SDR and transmitter: its boards, not this PC's.
        fun carteNoeud(k: String): String? = when (k) { "bladerf" -> "bladerf"; "pluto" -> "pluto"; else -> null }?.let { b.ajoute(it) }
        val sdrNoeud = if (noeud != null) carteNoeud(nm.s("sdr"))?.also { pourquoi += "SDR du SatMe-Node : " + nomRecepteur(nm.s("sdr")) } else null
        val txNoeud = if (noeud == null) null else if (nm.s("tx") == nm.s("sdr") && sdrNoeud != null) sdrNoeud
            else carteNoeud(nm.s("tx"))?.also { pourquoi += "Émetteur du SatMe-Node : " + nomRecepteur(nm.s("tx")) }
        listOfNotNull(sdrNoeud, txNoeud).distinct().forEach { b.relie(it, "usb", noeud!!, "usb") }

        // QO-100: the dish when something works there (an SDR board, the MiniTiouner, a converter).
        val olRx = conv.d("rxOl").takeIf { it > 0 }; val olTx = conv.d("txOl").takeIf { it > 0 }
        val fiRx = olRx?.let { (10489.75e6 - it) / 1e6 }
        val qo = sdrs.isNotEmpty() || mini != null || olRx != null
        val pc = b.ajoute("pc")
        if (qo) {
            val d = b.ajoute("parabole-qo100")
            pourquoi += "Parabole QO-100 : un récepteur ou un convertisseur de QO-100 est réglé"
            // The front end: a converter to the rig's band, else an LNB to 739 MHz.
            val versPoste = fiRx != null && poste != null && (fiRx in 120.0..160.0 || fiRx in 400.0..460.0)
            if (!versPoste && mini != null && sdrNoeud != null) {
                // The node's twin LNB: output 1 to the MiniTiouner (18 V), output 2 through a T (13 V) to the node's SDR.
                val lnb = b.ajoute("lnb-twin"); b.relie(d, "lnb", lnb, "entree"); b.relie(lnb, "sortie1", mini, "lnb_a")
                val t = b.ajoute("bias-t"); b.relie(lnb, "sortie2", t, "lnb"); b.relie(t, "rx", sdrNoeud, if (nm.s("sdr") == "bladerf") "rx1" else "rx")
                pourquoi += "LNB à deux sorties : MiniTiouner (18 V, horizontale) et SDR du node par un T de polarisation (13 V, verticale)"
            } else if (versPoste) {
                val dc = b.ajoute(if (fiRx!! < 300) "dc-144" else "dc-432")
                pourquoi += "Oscillateur de réception " + String.format(java.util.Locale.FRANCE, "%.3f", olRx!! / 1e6) + " MHz : FI vers " + Math.round(fiRx) + " MHz"
                b.relie(d, "lnb", dc, "entree")
                if (!b.relie(dc, "sortie", poste!!, if (fiRx < 300) "ant144" else "ant432")) b.relie(dc, "sortie", poste, "ant")
            } else {
                val lnb = b.ajoute("lnb-pll")
                b.relie(d, "lnb", lnb, "entree")
                val clients = ArrayList<Pair<String, String>>()
                if (mini != null) clients += mini to "lnb_a"
                for ((k, id) in sdrs) when (k) { "pluto" -> clients += id to "rx"; "bladerf" -> clients += id to "rx1"; "rtl" -> clients += id to "rx" }
                var source = lnb to "sortie"
                if (mini == null && clients.isNotEmpty()) {
                    val t = b.ajoute("bias-t"); b.relie(lnb, "sortie", t, "lnb"); source = t to "rx"
                    pourquoi += "T de polarisation : le LNB alimenté sans le MiniTiouner"
                }
                if (clients.size > 1) {
                    val r = b.ajoute("repartiteur"); b.relie(source.first, source.second, r, "entree")
                    b.relie(r, "sortie1", clients[0].first, clients[0].second)
                    // The MiniTiouner's LNB supply passes the splitter: a DC block keeps it off the SDR board.
                    if (mini != null) {
                        val k = b.ajoute("dc-block"); b.relie(r, "sortie2", k, "entree"); b.relie(k, "sortie", clients[1].first, clients[1].second)
                        pourquoi += "DC block : la tension du LNB (par le MiniTiouner) n'arrive pas sur la carte SDR"
                    } else b.relie(r, "sortie2", clients[1].first, clients[1].second)
                } else if (clients.size == 1) b.relie(source.first, source.second, clients[0].first, clients[0].second)
            }
            // The uplink: the DATV transmitter if one is set, else the rig through an up-converter.
            val emetteur = (if (dtx.b("parNoeud")) txNoeud else null) ?: dtx?.s("emetteur")?.let { sdrs[it] } ?: txNoeud
            val pa = b.ajoute("pa-2400")
            b.relie(pa, "sortie", d, "patch")
            when {
                emetteur != null -> { if (!b.relie(emetteur, "tx", pa, "entree") && !b.relie(emetteur, "tx1", pa, "entree")) b.relie(emetteur, "ant", pa, "entree") }
                poste != null && olTx != null -> {
                    val fiTx = kotlin.math.abs(2400.25e6 - olTx) / 1e6
                    val up = b.ajoute(if (fiTx < 300) "up-144" else "up-432")
                    pourquoi += "Oscillateur d'émission : montée depuis " + Math.round(fiTx) + " MHz"
                    b.relie(poste, if (fiTx < 300) "ant144" else "ant432", up, "entree"); b.relie(up, "sortie", pa, "entree")
                }
            }
        }
        // The cables to the computer.
        poste?.let { if (!b.relie(it, "usb", pc, "usb")) b.relie(it, "cat", pc, "serie") }
        sdrs.values.forEach { b.relie(it, "usb", pc, "usb") }
        if (mini != null) {
            if (noeud != null) { b.relie(mini, "usb", noeud, "usb"); b.relie(noeud, "lan", pc, "lan") } else b.relie(mini, "usb", pc, "usb")
        }
        if (l.etat?.optJSONObject("rotor").b("connecte")) {
            val r = b.ajoute("rotor-gs232"); b.relie(r, "serie", pc, "usb"); pourquoi += "Rotor connecté"
        }
        if (l.lien.b("on")) { val t = b.ajoute("telephone"); b.relie(pc, "wifi", t, "wifi"); pourquoi += "Lien satellite vers le téléphone en marche" }
        return Suggestion(b.chaine(), pourquoi)
    }
}
