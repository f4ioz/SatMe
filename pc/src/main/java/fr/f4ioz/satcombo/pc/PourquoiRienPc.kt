/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.PourquoiRien
import fr.f4ioz.satcombo.domain.PourquoiRien.Diagnostic
import fr.f4ioz.satcombo.domain.SourceAudio
import org.json.JSONObject

/**
 * « Pourquoi rien ? » on the PC: the facts each receiver's diagnosis needs,
 * taken from what SatMe-PC already holds (the rig's dial and mode as the
 * Doppler loop last read them, the sound card's level, the satellite's pass,
 * the SDR, the DATV receiver and the node, QO-100's beacon), handed to
 * [PourquoiRien]. Read only: nothing is tuned nor started here.
 */
class PourquoiRienPc(
    private val station: StationPc,
    private val son: SonStation?,
    private val sdr: SdrPc?,
    private val datv: DatvPc?,
    private val telemesurePc: () -> TelemesurePc?,
    private val qo100: Qo100Pc?,
    private val largePc: () -> SpectreLargePc?,
    private val balises: () -> BalisesQo100Pc?,
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    /** When each fact with no date of its own was first seen with its present value (« depuis »). */
    private val vus = HashMap<String, Pair<Any?, Long>>()

    @Synchronized private fun depuis(cle: String, valeur: Any?, t: Long): Long {
        val v = vus[cle]
        if (v != null && v.first == valeur) return v.second
        vus[cle] = valeur to t
        return t
    }

    // ------------------------------------------------------------ the facts

    private fun voies(): List<PourquoiRien.Voie> = station.etat.value.emetteurs.mapIndexedNotNull { i, t ->
        val lo = t.downlinkLowHz ?: return@mapIndexedNotNull null
        PourquoiRien.Voie(i, t.description, lo, t.downlinkHighHz, t.mode.orEmpty())
    }

    private fun ciel(t: Long): PourquoiRien.Ciel? {
        val e = station.etat.value
        val s = e.suivi ?: return null
        val aos = e.passage?.aosEpochMs?.takeIf { it > t } ?: e.passagesSuivi.firstOrNull { it.aosEpochMs > t }?.aosEpochMs
        return PourquoiRien.Ciel(s.name, s.catalogNumber, e.position?.elevationDeg, aos, voies(), e.emetteur, station.catSousHorizon)
    }

    /** The transmitter a function needs: the one chosen when it fits, else the first that fits. */
    private fun voiePour(ciel: PourquoiRien.Ciel?, convient: (fr.f4ioz.satcombo.data.Transmitter) -> Boolean): PourquoiRien.Voie? {
        ciel ?: return null
        val l = station.etat.value.emetteurs
        val i = l.getOrNull(ciel.voieChoisie)?.takeIf(convient)?.let { ciel.voieChoisie }
            ?: l.indexOfFirst { it.alive && convient(it) }.takeIf { it >= 0 } ?: return null
        return ciel.voies.firstOrNull { it.index == i }
    }

    private fun poste(t: Long): PourquoiRien.Poste? {
        val e = station.etat.value
        if (!e.catConnecte) return null
        val c = station.stationCat.etat.value
        val lu = station.reponduCatMs()
        val muet = if (e.catMuet) station.gardeCat.muetDepuisMs else null
        return PourquoiRien.Poste(fr.f4ioz.satcombo.cat.BandesPoste.libelle(e.catModele), c.descenteHz, lu, c.mode, lu, muet,
            e.maintienDoppler || station.qo100.etat.value.auPoste)
    }

    private fun sdrEtat(): PourquoiRien.Sdr? = sdr?.etat?.value?.let { PourquoiRien.Sdr(it.actif, it.cielHz, "", it.erreur) }

    private fun entree(t: Long): PourquoiRien.Entree? {
        val s = son?.son ?: return null
        val e = s.etat.value
        val m = s.mesure.instantane(t)
        return PourquoiRien.Entree(e.actif, e.appareil, m.dernierBlocMs, m.rmsDbfs, m.creteDbfs, m.fenetreMs)
    }

    private fun contexte(t: Long, parSdr: Boolean) = PourquoiRien.Contexte(t, entree(t), parSdr, sdrEtat(), poste(t), ciel(t))

    /** The card's decoders fed by the dongle now (it took the card's place). */
    private fun carteParSdr(): Boolean = son?.son?.etat?.value?.appareil == SdrPc.NOM_SOURCE

    // ------------------------------------------------------------ per receiver

    fun sstv(): Diagnostic? {
        val s = son ?: return null
        val t = horloge()
        val x = s.sstv
        val parSdr = x.sourceActive == SstvPc.SDR || (x.sourceActive.isEmpty() && x.source == SstvPc.SDR) || carteParSdr()
        val c = contexte(t, parSdr)
        val v = x.etat.value
        val enCours = if (v.mode.isNotBlank() && v.lignes > 0) "${v.mode}, ligne ${v.lignes}" + (if (v.hauteur > 0) "/${v.hauteur}" else "") else ""
        val voie = voiePour(c.ciel) { "SSTV" in SourceAudio.decodeursPour(it.mode.orEmpty(), it.description) }
        return PourquoiRien.sstv(c, s.sstvActif && x.actif, x.derniereImageMs, enCours, voie)
    }

    fun aprs(): Diagnostic? {
        val s = son ?: return null
        val t = horloge()
        val c = contexte(t, carteParSdr())
        val dernier = s.aprs.paquets.firstOrNull { !it.emis }?.quand ?: 0L
        val voie = voiePour(c.ciel) { "APRS" in SourceAudio.decodeursPour(it.mode.orEmpty(), it.description) }
        return PourquoiRien.aprs(c, s.aprsActif, dernier, voie)
    }

    fun ft8(): Diagnostic? {
        val s = son ?: return null
        val t = horloge()
        val f = s.ft8
        // The mode read outside the Doppler loop too (the FT8 tab asks for it).
        val c0 = contexte(t, carteParSdr())
        val p = c0.poste?.let { if (it.mode == null) it.copy(mode = runCatching { station.modePoste() }.getOrNull()) else it }
        val c = c0.copy(poste = p)
        val ch = station.emetteurChoisi()
        val lineaire = ch?.isTransponder == true && p?.dialHz != null &&
            p.dialHz in minOf(ch.downlinkLowHz ?: 0, ch.downlinkHighHz ?: 0) - 20_000L..maxOf(ch.downlinkLowHz ?: 0, ch.downlinkHighHz ?: 0) + 20_000L
        val derniere = f.entendus.maxOfOrNull { it.tranche } ?: 0L
        return PourquoiRien.ft8(c, f.enMarche, f.mode, derniere, f.sansDecodage, lineaire)
    }

    fun telemesure(): Diagnostic? {
        val tl = telemesurePc() ?: return null
        val t = horloge()
        val e = tl.paquets.etat
        val ciel = ciel(t)
        val em = tl.emetteurPaquets()
        val voie = em?.let { (_, desc, _) -> ciel?.voies?.firstOrNull { it.description == desc } }
        val p = PourquoiRien.Paquets(e.trames, e.crcFaux, e.derniereMs, e.niveauDb, e.plancherDb, e.signal, em?.first ?: e.debit)
        return PourquoiRien.telemesure(t, tl.paquetsActif, p, sdrEtat(), ciel, voie)
    }

    fun datv(): Diagnostic? {
        val d = datv ?: return null
        val t = horloge()
        val e = d.etat
        val r = d.reglage
        val muet = e.erreur.startsWith("node silencieux")
        val muetMs = depuis("noeud", muet, t).takeIf { muet }
        val b = balises()
        val ref = b?.historique?.points()?.lastOrNull { it.mer != null }?.let { it.mer!! to it.t }
        val avis = b?.historique?.courant("large")?.let { (a, ms) -> Triple(a.texte, a.cause.cle, ms) }
        return PourquoiRien.datv(t, PourquoiRien.Datv(e.actif, d.distant, e.etat, e.libelle, e.merDb, e.depuis, d.verrouDepuisMs,
            r.cielKhz, r.srKs, r.lnb, e.erreur, muetMs, ref, avis))
    }

    fun qo100(): Diagnostic? {
        val q = qo100 ?: return null
        val t = horloge()
        val s = sdrEtat()
        val sur = s?.actif == true && station.qo100.etat.value.aLaCle
        val d = q.suivi.dernier
        return PourquoiRien.qo100(t, s, sur, d?.rapportDb?.toDouble(), d?.t ?: 0L)
    }

    fun large(): Diagnostic? {
        val l = largePc() ?: return null
        return PourquoiRien.large(horloge(), l.on, l.frais(), l.etat, l.derniereTrameMs)
    }

    /** Every receiver's line, for the page (each tab shows its own). */
    fun json(): JSONObject {
        val t = horloge()
        val o = JSONObject().put("t", t)
        fun met(cle: String, d: () -> Diagnostic?) { runCatching { d() }.getOrNull()?.let { o.put(cle, it.json(t)) } }
        met("sstv") { sstv() }
        met("aprs") { aprs() }
        met("ft8") { ft8() }
        met("tlm") { telemesure() }
        met("datv") { datv() }
        met("qo100") { qo100() }
        met("large") { large() }
        return o
    }
}
