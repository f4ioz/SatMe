/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import fr.f4ioz.satcombo.station.Montage.Bout
import fr.f4ioz.satcombo.station.Montage.Chaine
import fr.f4ioz.satcombo.station.Montage.Element
import fr.f4ioz.satcombo.station.Montage.Lien
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * « Proposer d'après SatMe »: the station's chain guessed from what SatMe
 * already knows — the rig set and its CAT, the receivers and transmitters
 * of its tabs, the QO-100 chain's oscillators, the programs and services
 * set up — and from the USB devices the system lists (read-only files of
 * `/sys/bus/usb/devices`, the sound cards of `/proc/asound`).
 *
 * **Nothing is opened, nothing is asked of any device**: no serial port (a
 * rig's RTS/DTR keys it), no command to a radio or a board. Each piece
 * comes with how sure SatMe is ([Confiance]) and why; the owner keeps the
 * ones he wants ([fusionne]).
 */
object DetectionMontage {

    /** One USB device as the system lists it. */
    data class Usb(val vendeur: String, val produit: String, val nom: String = "", val fabricant: String = "") {
        val id get() = "$vendeur:$produit"
    }

    /** One sound card: its number, its name, its USB id when it is one ("08bb:2901"). */
    data class CarteSon(val index: Int, val id: String, val nom: String, val usb: String = "")

    enum class Confiance { DETECTE, SUPPOSE }

    /** A piece proposed: its element's id in the proposal, how sure, why. */
    data class Piece(val id: String, val confiance: Confiance, val raison: String)

    data class Proposition(val chaine: Chaine, val pieces: List<Piece>) {
        fun piece(id: String) = pieces.firstOrNull { it.id == id }
    }

    // ------------------------------------------------------------------ reading what the system lists (read-only)

    private fun lit(f: File): String = runCatching { f.readText().trim().take(120) }.getOrDefault("")

    /** The USB devices under [racine] (`/sys/bus/usb/devices`): only their id files read, the hubs left out. */
    fun litUsb(racine: File = File("/sys/bus/usb/devices")): List<Usb> =
        (racine.listFiles() ?: emptyArray()).sortedBy { it.name }.mapNotNull { d ->
            val v = lit(File(d, "idVendor")).lowercase(); val p = lit(File(d, "idProduct")).lowercase()
            if (!HEX4.matches(v) || !HEX4.matches(p) || lit(File(d, "bDeviceClass")) == "09") null
            else Usb(v, p, lit(File(d, "product")), lit(File(d, "manufacturer")))
        }

    private val HEX4 = Regex("[0-9a-f]{4}")
    private val LIGNE_CARTE = Regex("^\\s*(\\d+)\\s+\\[(.+?)\\s*]:\\s*(.*)$")

    /** The sound cards from `/proc/asound/cards`'s text, [usbid] the USB id of card n (its `usbid` file). */
    fun litCartes(cartes: String, usbid: (Int) -> String?): List<CarteSon> = cartes.lines().mapNotNull { l ->
        val m = LIGNE_CARTE.find(l) ?: return@mapNotNull null
        val n = m.groupValues[1].toInt()
        val nom = m.groupValues[3].substringAfter(" - ", m.groupValues[3]).trim()
        CarteSon(n, m.groupValues[2].trim(), nom, usbid(n)?.trim()?.lowercase()?.takeIf { Regex("[0-9a-f]{4}:[0-9a-f]{4}").matches(it) }.orEmpty())
    }

    fun litCartes(dossier: File = File("/proc/asound")): List<CarteSon> =
        litCartes(runCatching { File(dossier, "cards").readText().take(8000) }.getOrDefault("")) { n -> lit(File(dossier, "card$n/usbid")) }

    // ------------------------------------------------------------------ the guess

    private fun JSONObject?.b(k: String) = this?.optBoolean(k, false) == true
    private fun JSONObject?.s(k: String) = this?.optString(k, "").orEmpty()
    private fun JSONObject?.d(k: String) = this?.optDouble(k, Double.NaN) ?: Double.NaN
    private fun f3(hz: Double) = String.format(Locale.FRANCE, "%.3f", hz / 1e6)

    /** The USB ids SatMe knows: id → (model, what it is). */
    private val USB_CONNUS = mapOf(
        "0456:b673" to ("pluto" to "ADALM-PLUTO"), "2cf0:5250" to ("bladerf" to "bladeRF 2.0"), "1d50:6089" to ("hackrf" to "HackRF One"),
        "0bda:2838" to ("rtl-sdr" to "clé RTL-SDR"), "0bda:2832" to ("rtl-sdr" to "clé RTL-SDR"), "2341:484d" to ("molette-usb" to "molette de volume USB"))
    /** The sound codecs: Icom's (in the rig), the PCM290x of a USB sound card. */
    private const val CODEC_ICOM = "08bb:2901"
    private val CODECS_CARTE = setOf("08bb:2902", "08bb:2904", "08bb:29c0", "08bb:29c3", "08bb:29c6")

    private class Bati {
        var c = Chaine(nom = "Proposée par SatMe")
        val pieces = ArrayList<Piece>()
        private var n = 0
        fun ajoute(modele: String, confiance: Confiance, raison: String, options: Map<String, String> = emptyMap()): String {
            val id = "e" + (++n)
            c = c.copy(elements = c.elements + Element(id, modele, "", options))
            pieces += Piece(id, confiance, raison)
            return id
        }
        fun modele(id: String) = c.element(id)?.modele
        fun premier(vararg m: String) = c.elements.firstOrNull { it.modele in m }?.id
        fun relie(a: String?, pa: String, b: String?, pb: String): Boolean {
            if (a == null || b == null) return false
            val de = Bout(a, pa); val vers = Bout(b, pb)
            val x = Montage.port(c, de) ?: return false; val y = Montage.port(c, vers) ?: return false
            val k = Montage.cableParDefaut(x, y) ?: return false
            if (Montage.refus(c, de, vers, k) != null) return false
            c = c.copy(liens = c.liens + Lien("l" + (c.liens.size + 1), de, vers, k))
            return true
        }
        /** The first of [ports] of [a] that takes a cable to [b].[pb]. */
        fun relieUn(a: String?, ports: List<String>, b: String?, pb: String) = ports.any { relie(a, it, b, pb) }
    }

    /** The guess from SatMe's state [l] and the system's lists [usb] and [cartes]. */
    fun propose(l: ActiviteMontage.Lectures, usb: List<Usb> = emptyList(), cartes: List<CarteSon> = emptyList()): Proposition {
        val b = Bati()
        val ids = usb.map { it.id }
        val pc = b.ajoute("pc", Confiance.DETECTE, "Ce PC, où tourne SatMe-PC")

        // ---- the rig: the one set in Réglages, its CAT connected; an Icom known by its USB name or codec
        val cat = l.etat?.optJSONObject("cat")
        val icomUsb = usb.firstOrNull { it.nom.contains("IC-9700", true) }
        val codecIcom = CODEC_ICOM in ids || cartes.any { it.usb == CODEC_ICOM }
        val posteModele = when (cat.s("modele")) {
            "IC9700", "SIM" -> "ic9700"; "TS2000", "TS2000SIM" -> "ts2000"; "PAIRE" -> "ic705"; "" -> null; else -> "poste"
        }
        val catVrai = cat.b("connecte") && !cat.b("simule")
        val poste: String? = when {
            posteModele != null -> b.ajoute(posteModele, if (catVrai || (posteModele == "ic9700" && (icomUsb != null || codecIcom))) Confiance.DETECTE else Confiance.SUPPOSE,
                (if (catVrai) "CAT connecté : " else "Choisi dans Réglages › Poste : ") + cat.s("modele") +
                    (if (posteModele == "ic9700" && icomUsb != null) " ; « " + icomUsb.nom + " » sur l'USB" else "") +
                    (if (posteModele == "ic9700" && codecIcom) " ; son codec USB ($CODEC_ICOM)" else ""))
            icomUsb != null -> b.ajoute("ic9700", Confiance.DETECTE, "« " + icomUsb.nom + " » sur l'USB")
            codecIcom -> b.ajoute("ic9700", Confiance.SUPPOSE, "Codec son de poste Icom sur l'USB ($CODEC_ICOM)")
            else -> null
        }

        // ---- the SDR boards: on the USB, else as the tabs know them
        val sdr = l.sdr?.takeIf { !it.b("absent") }
        val datv = l.datv?.takeIf { it.optBoolean("ok", true) }
        val dtx = l.emissionDatv?.takeIf { it.optBoolean("ok", true) }
        val cartesSdr = LinkedHashMap<String, String>()   // board key -> element
        fun cle(m: String) = when (m) { "rtl-sdr" -> "rtl"; else -> m }
        for (u in usb) {
            val (m, nom) = USB_CONNUS[u.id] ?: continue
            if (m == "molette-usb") { if (b.premier("molette-usb") == null) b.ajoute(m, Confiance.DETECTE, "Molette USB (${u.id})"); continue }
            if (cle(m) in cartesSdr) continue
            cartesSdr[cle(m)] = b.ajoute(m, Confiance.DETECTE, nom + " sur l'USB (" + u.id + (if (u.nom.isNotBlank()) ", « " + u.nom + " »" else "") + ")")
        }
        fun parEtat(k: String, raison: String, sur: Boolean) {
            if (k in cartesSdr) return
            val m = when (k) { "pluto" -> "pluto"; "bladerf" -> "bladerf"; "hackrf" -> "hackrf"; "rtl" -> "rtl-sdr"; else -> return }
            cartesSdr[k] = b.ajoute(m, if (sur) Confiance.DETECTE else Confiance.SUPPOSE, raison)
        }
        sdr?.s("recepteur")?.takeIf { it.isNotBlank() && (it != "rtl" || sdr.b("actif") || sdr.s("tuner").isNotBlank()) }?.let {
            parEtat(it, "Récepteur de l'onglet SDR" + (if (it == "pluto") " (par le réseau, " + sdr.s("plutoHote").ifBlank { "192.168.2.1" } + ")" else ""), sdr.b("actif"))
        }
        if (dtx?.optJSONObject("bladerf").let { it.b("lu") || it.s("serie").isNotBlank() }) parEtat("bladerf", "bladeRF lu par SatMe (onglet DATV)", true)
        if ((dtx?.optJSONObject("hackrf")?.optJSONArray("cartes")?.length() ?: 0) > 0) parEtat("hackrf", "HackRF listé par SatMe (onglet DATV)", true)
        dtx?.s("emetteur")?.takeIf { it.isNotBlank() }?.let { parEtat(it, "Émetteur DATV choisi", false) }
        datv?.s("recepteur")?.takeIf { it == "pluto" || it == "bladerf" }?.let { parEtat(it, "Récepteur DATV choisi", datv.b("actif")) }

        // ---- the MiniTiouner: its FTDI chip, its LongMynd receiver set
        val ftdi = usb.firstOrNull { it.id == "0403:6010" }
        val miniReglee = datv?.s("recepteur") == "minitiouner"
        val noeudNom = datv.s("noeud").takeIf { miniReglee }.orEmpty()
        val mini = when {
            ftdi != null && (ftdi.nom.contains("tiouner", true) || miniReglee) -> b.ajoute("minitiouner", Confiance.DETECTE,
                "Puce FTDI double sur l'USB (0403:6010" + (if (ftdi.nom.isNotBlank()) ", « " + ftdi.nom + " »" else "") + ")" + (if (miniReglee) " et récepteur DATV réglé" else ""))
            ftdi != null -> b.ajoute("minitiouner", Confiance.SUPPOSE, "Puce FTDI double sur l'USB (0403:6010) : peut-être un MiniTiouner")
            miniReglee -> b.ajoute("minitiouner", if (noeudNom.isNotBlank()) Confiance.DETECTE else Confiance.SUPPOSE,
                "Récepteur DATV réglé : MiniTiouner" + (if (noeudNom.isNotBlank()) " sur le SatMe-Node « $noeudNom »" else ""))
            else -> null
        }
        val noeud = if (mini != null && noeudNom.isNotBlank()) b.ajoute("satme-node", Confiance.DETECTE, "MiniTiouner joint par le SatMe-Node « $noeudNom »") else null

        // ---- QO-100: the chain's oscillators say what stands between the dish and the receivers
        val q = l.qo100
        val conv = l.etat?.optJSONObject("convertisseurs")
        val olRx = q.d("olRx").takeIf { it > 1e8 } ?: conv.d("rxOl").takeIf { it > 1e8 }
        val olTx = q.d("olTx").takeIf { it > 1e8 } ?: conv.d("txOl").takeIf { it > 1e8 }
        val nomChaine = q.s("chaine").let { if (it.isNotBlank()) " (chaîne « $it »)" else "" }
        val descente = 10489.75e6
        val ecartLnb = olRx?.let { it - LO_LNB }
        val sdrRx = listOf("pluto" to "rx", "bladerf" to "rx1", "rtl" to "rx", "hackrf" to "ant").firstOrNull { it.first in cartesSdr }?.let { cartesSdr[it.first]!! to it.second }
        val qo = olRx != null || mini != null || cartesSdr.isNotEmpty()
        if (qo) {
            val d = b.ajoute("parabole-qo100", Confiance.SUPPOSE, "Parabole QO-100 : un récepteur" + (if (olRx != null) " et l'oscillateur de la chaîne QO-100" else "") + " le disent")
            // A down-converter after the LNB: the oscillator is the LNB's plus the converter's (about 595 MHz for 144, 307 for 432).
            val sortieDc = when {
                ecartLnb == null -> null
                ecartLnb in 500e6..650e6 -> 144
                ecartLnb in 250e6..350e6 -> 432
                else -> null
            }
            val fiSeule = olRx?.let { (descente - it) / 1e6 }
            when {
                sortieDc != null -> {
                    val twin = mini != null
                    val lnb = b.ajoute(if (twin) "lnb-twin" else "lnb-pll", Confiance.SUPPOSE,
                        "LNB à " + f3(LO_LNB) + " MHz (FI " + f3(descente - LO_LNB) + " MHz)" + nomChaine + (if (twin) " ; deux sorties : MiniTiouner et convertisseur" else ""))
                    val dc = b.ajoute("dxp-down-mk4", Confiance.SUPPOSE, "Convertisseur descendant 739 → $sortieDc MHz : oscillateur de la chaîne " + f3(olRx!!) +
                        " MHz = LNB + " + f3(ecartLnb!!) + " MHz (modèle à vérifier dans sa fiche)", mapOf("sortie" to sortieDc.toString()))
                    b.relie(d, "lnb", lnb, "entree")
                    if (twin) { b.relie(lnb, "sortie1", mini, "lnb_a"); b.relie(lnb, "sortie2", dc, "entree") } else b.relie(lnb, "sortie", dc, "entree")
                    // The converter's output: the SDR board when it listens to QO-100 (or without a rig), else the rig on its band.
                    val versSdr = sdrRx != null && (poste == null || q.b("aLaCle") || sdr.b("actif"))
                    if (versSdr) b.relie(dc, "sortie", sdrRx!!.first, sdrRx.second)
                    else if (poste != null && !b.relie(dc, "sortie", poste, if (sortieDc == 144) "ant144" else "ant432")) b.relie(dc, "sortie", poste, "ant")
                }
                fiSeule != null && poste != null && (fiSeule in 120.0..160.0 || fiSeule in 400.0..460.0) -> {
                    val dc = b.ajoute(if (fiSeule < 300) "dc-144" else "dc-432", Confiance.SUPPOSE, "Oscillateur de réception " + f3(olRx!!) + " MHz" + nomChaine + " : FI vers " + Math.round(fiSeule) + " MHz")
                    b.relie(d, "lnb", dc, "entree")
                    if (!b.relie(dc, "sortie", poste, if (fiSeule < 300) "ant144" else "ant432")) b.relie(dc, "sortie", poste, "ant")
                }
                else -> {
                    val lnb = b.ajoute("lnb-pll", Confiance.SUPPOSE, if (olRx != null) "LNB à " + f3(olRx) + " MHz" + nomChaine + " (FI " + f3(descente - olRx) + " MHz)" else "LNB : un récepteur de QO-100 est réglé")
                    b.relie(d, "lnb", lnb, "entree")
                    val clients = ArrayList<Pair<String, String>>()
                    if (mini != null) clients += mini to "lnb_a"
                    sdrRx?.let { clients += it }
                    var source = lnb to "sortie"
                    if (mini == null && clients.isNotEmpty()) {
                        val t = b.ajoute("bias-t", Confiance.SUPPOSE, "T de polarisation : le LNB alimenté sans le MiniTiouner")
                        b.relie(lnb, "sortie", t, "lnb"); source = t to "rx"
                    }
                    if (clients.size > 1) {
                        val r = b.ajoute("repartiteur", Confiance.SUPPOSE, "Répartiteur : une sortie du LNB pour deux récepteurs")
                        b.relie(source.first, source.second, r, "entree")
                        b.relie(r, "sortie1", clients[0].first, clients[0].second); b.relie(r, "sortie2", clients[1].first, clients[1].second)
                    } else if (clients.size == 1) b.relie(source.first, source.second, clients[0].first, clients[0].second)
                }
            }
            // The uplink: an up-converter from the rig's band (the chain's transmit oscillator), or the DATV transmitter straight on.
            val fiTx = olTx?.let { kotlin.math.abs(2400.25e6 - it) / 1e6 }
            val emetteur = dtx?.s("emetteur")?.let { cartesSdr[it] }
            if (fiTx != null && fiTx in 120.0..460.0) {
                val bandeFi = if (fiTx < 300) 144 else 432
                val up = b.ajoute("dxp-up-mk4", Confiance.SUPPOSE, "Convertisseur montant $bandeFi → 2400 MHz : oscillateur d'émission " + f3(olTx!!) + " MHz (modèle à vérifier dans sa fiche)",
                    mapOf("fi" to bandeFi.toString()))
                val pa = b.ajoute("pa-2400", Confiance.SUPPOSE, "Amplificateur 2,4 GHz après le convertisseur montant")
                b.relie(up, "sortie", pa, "entree"); b.relie(pa, "sortie", d, "patch")
                if (poste != null) { if (!b.relie(poste, if (bandeFi == 144) "ant144" else "ant432", up, "entree")) b.relie(poste, "ant", up, "entree") }
                else if (emetteur != null) b.relieUn(emetteur, listOf("tx", "tx1", "ant"), up, "entree")
            } else if (emetteur != null) {
                val pa = b.ajoute("pa-2400", Confiance.SUPPOSE, "Amplificateur 2,4 GHz après l'émetteur DATV")
                b.relieUn(emetteur, listOf("tx", "tx1", "ant"), pa, "entree"); b.relie(pa, "sortie", d, "patch")
            }
        }

        // ---- references: a Leo Bodnar GPSDO is a USB device
        usb.firstOrNull { it.vendeur == "1dd2" }?.let { u ->
            val gp = b.ajoute("leo-bodnar", if (u.nom.contains("GPS", true) || u.nom.contains("clock", true)) Confiance.DETECTE else Confiance.SUPPOSE,
                "GPSDO sur l'USB (" + u.id + (if (u.nom.isNotBlank()) ", « " + u.nom + " »" else "") + ")")
            val ant = b.ajoute("antenne-gps", Confiance.SUPPOSE, "Son antenne GPS")
            b.relie(ant, "gps", gp, "gps"); b.relie(gp, "usb", pc, "usb")
        }

        // ---- sound cards, the knob, an ESP32 board
        val codecCarte = usb.firstOrNull { it.id in CODECS_CARTE }?.id ?: cartes.firstOrNull { it.usb in CODECS_CARTE }?.usb
        val carte = codecCarte?.let { k ->
            val nom = cartes.firstOrNull { it.usb == k }?.let { " « " + it.nom + " » (carte " + it.index + ")" }.orEmpty()
            b.ajoute("carte-son", Confiance.DETECTE, "Carte son USB PCM290x ($k)$nom")
        }
        usb.firstOrNull { it.vendeur == "303a" }?.let { u -> b.ajoute("interface-esp32", Confiance.SUPPOSE, "Carte Espressif sur l'USB (" + u.id + ") : peut-être le boîtier de station ESP32-S3") }

        // ---- the cables to the computer
        if (poste != null) {
            when (b.modele(poste)) {
                "ts2000" -> { b.relie(poste, "cat", pc, "serie"); if (carte != null) b.relie(poste, "acc2", carte, "audio") }
                "ft818" -> { b.relie(poste, "cat", pc, "serie"); if (carte != null) b.relie(poste, "data", carte, "audio") }
                else -> if (!b.relie(poste, "usb", pc, "usb")) b.relie(poste, "cat", pc, "serie")
            }
        }
        cartesSdr.values.forEach { b.relie(it, "usb", pc, "usb") }
        if (mini != null) { if (noeud != null) { b.relie(mini, "usb", noeud, "usb"); b.relie(noeud, "lan", pc, "lan") } else b.relie(mini, "usb", pc, "usb") }
        carte?.let { b.relie(it, "usb", pc, "usb") }
        b.premier("molette-usb")?.let { b.relie(it, "usb", pc, "usb") }
        b.premier("interface-esp32")?.let { b.relie(it, "usb", pc, "usb") }
        if (l.etat?.optJSONObject("rotor").b("connecte")) {
            val r = b.ajoute("rotor-gs232", Confiance.DETECTE, "Rotor connecté"); b.relie(r, "serie", pc, "usb")
        }

        // ---- the boîtes: what is set up or on
        val bx = ActiviteMontage.boites(l.boites)
        fun boite(cle: String, modele: String, raison: String) {
            val e = bx[cle] ?: return
            if (e.etat == ActiviteMontage.EtatBoite.ARRET) return
            val id = b.ajoute(modele, Confiance.DETECTE, raison + " : " + e.texte)
            b.relie(id, "net", pc, "logiciel")
        }
        boite("wavelog", "boite-wavelog", "Wavelog réglé (Réglages › Carnet)")
        boite("qrz", "boite-qrz", "QRZ.com réglé")
        boite("psk", "boite-psk", "PSK Reporter choisi")
        boite("chat", "boite-chat", "Spectre large bande et chat en marche")
        boite("carnets", "boite-carnets", "Programmes de carnet (rigctld)")
        boite("lien", "boite-lien", "Lien satellite en marche")
        boite("telephone", "boite-telephone", "Téléphone appairé")
        boite("ecoute", "boite-ecoute", "« Écouter ici » dans un navigateur")
        return Proposition(Montage.nettoie(b.c), b.pieces)
    }

    /** The LNB's usual oscillator for QO-100. */
    const val LO_LNB = 9750e6

    // ------------------------------------------------------------------ taken piece by piece

    /** What the owner kept merged: the chain, how many pieces and cables were added. */
    data class Fusion(val chaine: Chaine, val ajoutes: Int, val relies: Int)

    /**
     * The pieces [garde] of [p] put into [actuelle]: one already there (the
     * same model, not yet matched) is reused, the others added; the cables
     * between pieces kept, unless already there or impossible.
     */
    fun fusionne(actuelle: Chaine, p: Chaine, garde: Set<String>): Fusion {
        var c = actuelle
        val pris = HashSet<String>(); val vers = HashMap<String, String>()
        var ajoutes = 0; var relies = 0
        for (e in p.elements) {
            if (e.id !in garde) continue
            val deja = c.elements.firstOrNull { it.modele == e.modele && it.id !in pris }
            if (deja != null) { pris += deja.id; vers[e.id] = deja.id; continue }
            if (c.elements.size >= Montage.ELEMENTS_MAX) break
            val id = Montage.nouvelId(c, "e")
            c = c.copy(elements = c.elements + e.copy(id = id, x = null, y = null))
            pris += id; vers[e.id] = id; ajoutes++
        }
        for (l in p.liens) {
            val a = vers[l.de.element] ?: continue; val b = vers[l.vers.element] ?: continue
            val de = Bout(a, l.de.port); val ve = Bout(b, l.vers.port)
            if (c.liens.any { (it.de == de && it.vers == ve) || (it.de == ve && it.vers == de) }) continue
            if (c.liens.size >= Montage.LIENS_MAX || Montage.refus(c, de, ve, l.cable) != null) continue
            c = c.copy(liens = c.liens + Lien(Montage.nouvelId(c, "l"), de, ve, l.cable, l.note)); relies++
        }
        return Fusion(Montage.nettoie(c), ajoutes, relies)
    }

    fun json(p: Proposition): JSONObject = JSONObject().put("ok", true).put("chaine", Montage.ecrit(p.chaine))
        .put("plan", DispositionMontage.json(DispositionMontage.dispose(p.chaine)))
        .put("ports", Montage.portsJson(p.chaine))
        .put("pieces", JSONArray(p.pieces.filter { p.chaine.element(it.id) != null }.map {
            JSONObject().put("id", it.id).put("confiance", it.confiance.name.lowercase()).put("raison", it.raison)
        }))
        .put("pourquoi", JSONArray(p.pieces.filter { p.chaine.element(it.id) != null }.map { it.raison }))
}
