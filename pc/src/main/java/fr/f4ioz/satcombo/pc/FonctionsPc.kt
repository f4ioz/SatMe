/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.station.ActiviteMontage
import fr.f4ioz.satcombo.station.FonctionsMontage
import fr.f4ioz.satcombo.station.ProfilsFonction
import org.json.JSONObject

/**
 * The « Montage » page's view of SatMe-PC's functions: the state read from
 * the parts (the same the tabs read), and the receive gestures and profiles
 * done through the page's own routes ([action]), so each keeps its checks.
 *
 * **Receive only**: [fait] knows the keys of [ProfilsFonction.CLES] and
 * nothing else — no transmission, no LNB supply, no serial port.
 */
class FonctionsPc(
    private val station: StationPc,
    private val sdr: SdrPc?,
    private val son: SonStation?,
    private val datv: DatvPc?,
    private val phonie: PhoniePc?,
    private val diffusion: DiffusionSon?,
    /** The page's POST routes (ServeurPc's), as the tabs call them. */
    private val action: (String, Map<String, String>) -> JSONObject,
    /** The montage's readings (the routes' JSON), read again for each look. */
    private val lectures: () -> ActiviteMontage.Lectures,
) : ProfilsFonction.Executant {

    /** The functions' state, from [l] (the montage's readings) and the sound's and decoders' parts. */
    fun instantane(l: ActiviteMontage.Lectures = lectures()): FonctionsMontage.Instantane {
        val s = l.sdr?.takeIf { !it.optBoolean("absent", false) && sdr != null }
        val d = l.datv?.takeIf { it.optBoolean("ok", true) && datv != null }
        val e = l.emissionDatv; val x = l.emissionSstv; val iq = l.iq
        val cat = l.etat?.optJSONObject("cat")
        val so = son
        val c = so?.son?.etat?.value
        val ph = phonie
        val noms = so?.nomsCartes(cartes()).orEmpty()
        fun emet(o: JSONObject?, k: String) = if (o?.optBoolean("emet", false) == true) k.ifBlank { "pluto" } else ""
        val i = FonctionsMontage.Instantane(
            sdrPresent = s != null, sdrActif = s?.optBoolean("actif") == true, sdrRecepteur = s?.optString("recepteur").orEmpty().ifBlank { "rtl" },
            sdrErreur = s?.optString("erreur").orEmpty(), sdrMode = s?.optString("mode").orEmpty(), sdrCielHz = s?.optLong("ciel") ?: 0L,
            sdrManuelleHz = s?.optLong("manuelle") ?: 0L, sdrSuit = s?.optBoolean("suit") == true,
            sdrPourSonde = sdr?.pourSonde == true, sdrPourMeteor = sdr?.pourMeteor == true, sdrHautParleur = s?.optBoolean("son") == true,
            qo100ALaCle = runCatching { station.qo100.etat.value.aLaCle }.getOrDefault(false),
            datvPresent = d != null, datvActif = d?.optBoolean("actif") == true, datvRecepteur = d?.optString("recepteur").orEmpty().ifBlank { "minitiouner" },
            datvSdrTenu = d?.optString("sdrTenu").orEmpty(), datvVerrouille = d?.optBoolean("verrouille") == true,
            datvSon = d?.optBoolean("son") == true, datvErreur = d?.optString("erreur").orEmpty(),
            txDatv = emet(e, e?.optString("emetteurEnCours").orEmpty().ifBlank { e?.optString("emetteur").orEmpty() }),
            txSstv = emet(x, x?.optJSONObject("enCours")?.optString("emetteur").orEmpty().ifBlank { x?.optString("emetteur").orEmpty() }),
            txIq = if (iq?.optBoolean("actif") == true) iq.optString("appareil").ifBlank { "pluto" } else "",
            txDatvChoisi = e?.optString("emetteur").orEmpty(), txSstvChoisi = x?.optString("emetteur").orEmpty(),
            sonPresent = so != null, sonActif = c?.actif == true, sonAppareil = c?.appareil.orEmpty(), sonCarte = so?.carte.orEmpty(),
            sonParSdr = c?.appareil == SdrPc.NOM_SOURCE, cartes = cartes().map { it.nom }, sonErreur = c?.erreur.orEmpty(),
            sstvActif = so?.sstvActif == true, sstvSource = so?.sstv?.source ?: "auto", sstvSourceActive = so?.sstv?.sourceActive.orEmpty(),
            ft8Actif = so?.ft8?.enMarche == true, ft8Mode = so?.ft8?.mode ?: "FT8", cwActif = so?.cw?.actif == true, aprsActif = so?.aprsActif == true,
            phoniePresent = ph != null, phonieActif = ph?.actif == true, phonieSource = ph?.source ?: "auto", phonieSourceActive = ph?.sourceActive.orEmpty(),
            meteorPresent = sdr?.meteor != null,
            catConnecte = cat?.optBoolean("connecte") == true, catModele = cat?.optString("modele").orEmpty(), catSimule = cat?.optBoolean("simule") == true,
            ecouteNavigateur = diffusion?.ecoute("sdr") == true,
            libelles = noms.mapValues { it.value.libelle }, carteDuPoste = noms.entries.firstOrNull { it.value.poste.isNotBlank() }?.key.orEmpty(),
        )
        return i
    }

    // The sound cards Java knows, read again every ten seconds (the list asks every mixer).
    @Volatile private var cartesLues: Pair<Long, List<SonPc.Appareil>> = 0L to emptyList()
    private fun cartes(): List<SonPc.Appareil> {
        val (t, l) = cartesLues
        if (System.currentTimeMillis() - t < 10_000L) return l
        val n = son?.son?.appareils().orEmpty()
        cartesLues = System.currentTimeMillis() to n
        return n
    }

    /** The transmission on the air, read from the parts now (before each step). */
    override fun emission(): String? = instantane().emission()

    private fun reponse(o: JSONObject?): String? =
        if (o == null || o.optBoolean("ok", true)) null else o.optString("erreur").ifBlank { "refusé" }

    override fun fait(cle: String, valeur: String): String? {
        if (!ProfilsFonction.admis(cle, valeur)) return "réglage refusé"
        fun sdrReglage(k: String) = reponse(action("/api/sdr/reglage", mapOf(k to valeur)))
        return when (cle) {
            "sdr.recepteur" -> sdrReglage("recepteur")
            "sdr.mode" -> sdrReglage("mode")
            "sdr.mhz" -> sdrReglage("mhz")
            "sdr.suit" -> sdrReglage("suit")
            "sdr.son" -> sdrReglage("son")
            "sdr.actif" -> reponse(action(if (valeur == "1") "/api/sdr/demarrer" else "/api/sdr/arreter", emptyMap()))
            "datv.recepteur" -> { val d = datv ?: return "pas de DATV ici"; d.recepteur = valeur; null }
            "datv.actif" -> when {
                valeur == "1" -> "la réception DATV se démarre dans l'onglet DATV"
                datv?.recepteur == fr.f4ioz.satcombo.datv.Datv.MINITIOUNER -> "le MiniTiouner s'arrête dans l'onglet DATV (il alimente le LNB)"
                else -> reponse(action("/api/datv/arrete", emptyMap()))
            }
            "son.entree" -> when (valeur) {
                "" -> reponse(action("/api/son/carte", mapOf("nom" to "")))
                "sdr" -> {
                    val s = sdr ?: return "pas de carte SDR ici"
                    if (!s.etat.value.actif) return "le récepteur SDR est arrêté"
                    // The SDR takes the decoders' input when it starts: the card stopped, the receiver started again.
                    reponse(action("/api/son/carte", mapOf("nom" to ""))) ?: reponse(action("/api/sdr/demarrer", emptyMap()))
                }
                else -> reponse(action("/api/son/carte", mapOf("nom" to valeur)))
            }
            "sstv.source" -> reponse(action("/api/sstv/reglage", mapOf("source" to valeur)))
            "sstv.actif" -> reponse(action("/api/sstv/reglage", mapOf("actif" to valeur)))
            "ft8.mode" -> reponse(action("/api/ft8", mapOf("mode" to valeur)))
            "ft8.actif" -> reponse(action("/api/ft8", mapOf("on" to valeur)))
            "cw.actif" -> reponse(action("/api/cw", mapOf("on" to valeur)))
            "aprs.actif" -> reponse(action("/api/son/options", mapOf("aprs" to valeur)))
            "phonie.source" -> phonie?.let { reponse(it.action("/api/phonie", mapOf("source" to valeur))) } ?: "pas de « Phonie → texte » ici"
            "phonie.actif" -> phonie?.let { reponse(it.action("/api/phonie", mapOf("on" to valeur))) } ?: "pas de « Phonie → texte » ici"
            else -> "réglage inconnu"
        }
    }
}
