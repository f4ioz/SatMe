/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.cat.icomreseau.ApprentissageTrace
import fr.f4ioz.satcombo.cat.icomreseau.CodageIdentifiants
import fr.f4ioz.satcombo.cat.icomreseau.CodecReseau
import fr.f4ioz.satcombo.cat.icomreseau.PosteIcomReseau
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The IC-9700 by the network on the page: its settings (Réglages › Poste),
 * its status, its receive audio as a sound source, its scope. Nothing is
 * opened until the CAT connects with « Réseau » or the source is started.
 */
class IcomReseauPc(private val station: StationPc, private val son: SonPc, val dossier: File) {

    private val poste = station.icomReseau
    private val s = station.settings
    val fichierTrace = File(dossier, "icom-reseau-trace.txt")
    private val heure = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    init {
        poste.ecrisJournal = { ligne -> ecrisTrace(ligne) }
        son.virtuelles += SonPc.SourceVirtuelle(PosteIcomReseau.NOM_SOURCE, "Icom par le réseau (réception)",
            disponible = { s.icomHote.isNotBlank() },
            demarre = { distribue ->
                // Set to IF, the rig's sound is no sound: the decoders would only get noise.
                if (sonFi) FI_PAS_SON else {
                    val a: (ShortArray, Int, Int) -> Unit = { pcm, n, rate -> distribue(pcm, n, rate) }
                    ecouteur = a
                    poste.auditeurs += a
                    poste.prends(PosteIcomReseau.Usage.AUDIO)?.also { poste.auditeurs -= a }
                }
            },
            arrete = {
                ecouteur?.let { poste.auditeurs -= it }; ecouteur = null
                // At once, not later: a restart right after must not see its session closed under it.
                poste.rends(PosteIcomReseau.Usage.AUDIO)
            })
    }

    @Volatile private var ecouteur: ((ShortArray, Int, Int) -> Unit)? = null

    companion object {
        /** The largest capture taken for learning the coding. */
        const val TRACE_MAX = 50L * 1024 * 1024
        const val CLE_SON_FI = "pc_poste_son_fi"
        const val CLE_FI_TRANSPORT = "pc_poste_fi_transport"
        const val CLE_FI_CARTE = "pc_poste_fi_carte"
        const val CLE_FI_INVERSE = "pc_poste_fi_inverse"
        const val FI_PAS_SON = "le poste envoie sa FI (Réglages › Poste › Son du poste) : le son démodulé vient de l'onglet SDR, récepteur « IC-9700 FI »"
    }

    // ------------------------------------------------------------ the rig's IF (« IC-9700 FI » in the SDR tab)

    private val stockage = station.stockage
    /** The rig's sound set to IF (its menu), demodulated by SatMe; off: AF, demodulated by the rig. */
    var sonFi: Boolean
        get() = stockage.getBoolean(CLE_SON_FI, false)
        set(v) { stockage.edit().putBoolean(CLE_SON_FI, v).apply() }
    /** Where the IF comes from: "reseau" (16 kHz, ±3.6 kHz usable) or "usb" (the rig's sound card, 48 kHz, ±7.5 kHz). */
    var fiTransport: String
        get() = stockage.getString(CLE_FI_TRANSPORT, "reseau").takeIf { it == "usb" } ?: "reseau"
        set(v) { stockage.edit().putString(CLE_FI_TRANSPORT, if (v == "usb") "usb" else "reseau").apply() }
    /** The rig's USB sound card (Java's name); empty: the one recognised as the rig's. */
    var fiCarte: String
        get() = stockage.getString(CLE_FI_CARTE, "") ?: ""
        set(v) { stockage.edit().putString(CLE_FI_CARTE, v.trim()).apply() }
    /** The IF turned over once more (a rig whose IF runs the other way). */
    var fiInverse: Boolean
        get() = stockage.getBoolean(CLE_FI_INVERSE, false)
        set(v) { stockage.edit().putBoolean(CLE_FI_INVERSE, v).apply() }
    /** The cards' friendly names and the rig's own (set by Main). */
    @Volatile var sonStation: SonStation? = null

    /** The sound cards (not the network source): Java's name and what to show. */
    fun cartes(): JSONObject {
        val l = son.appareils().filter { a -> son.virtuelles.none { it.nom == a.nom } }
        val noms = runCatching { sonStation?.nomsCartes(l) }.getOrNull().orEmpty()
        return JSONObject().put("carte", fiCarte).put("poste", runCatching { sonStation?.carteDuPoste(l) }.getOrNull() ?: "")
            .put("cartes", JSONArray(l.map { JSONObject().put("nom", it.nom).put("libelle", noms[it.nom]?.libelle ?: it.nom) }))
    }

    /**
     * The rig's IF opened as a receiver for the SDR tab, by the transport
     * chosen; throws with the reason. Receive only: the network session's
     * audio channel or the card's capture, nothing sent to the rig.
     */
    fun ouvreSourceFi(): fr.f4ioz.satcombo.sdr.SourceFiPoste {
        val boite = arrayOfNulls<fr.f4ioz.satcombo.sdr.SourceFiPoste>(1)
        // The simulated IC-9700: a made-up IF at the transport's rate, at its pace; nothing opened.
        if (station.etat.value.catSimule) {
            val fs = if (fiTransport == "usb") 48_000 else 16_000
            val marche = java.util.concurrent.atomic.AtomicBoolean(true)
            val src = fr.f4ioz.satcombo.sdr.SourceFiPoste(fs, fiTransport, fiInverse, ferme = { marche.set(false) })
            kotlin.concurrent.thread(isDaemon = true, name = "satme-fi-simulee") {
                val g = fr.f4ioz.satcombo.sdr.FiSimulee(fs)
                val b = ShortArray(fs / 50)
                var suivant = System.nanoTime()
                while (marche.get()) {
                    g.bloc(b, b.size); src.pousse(b, b.size)
                    suivant += 20_000_000L
                    val d = (suivant - System.nanoTime()) / 1_000_000L
                    if (d > 0) Thread.sleep(d)
                }
            }
            return src
        }
        if (fiTransport == "usb") {
            val nom = fiCarte.ifBlank { runCatching { sonStation?.carteDuPoste() }.getOrNull().orEmpty() }
            if (nom.isBlank()) throw java.io.IOException("carte son de l'IC-9700 à choisir (Réglages › Poste › Son du poste)")
            val (r, pourquoi) = son.capture(nom) { pcm, n, _ -> boite[0]?.pousse(pcm, n) }
            val (rate, ferme) = r ?: throw java.io.IOException(pourquoi)
            return runCatching { fr.f4ioz.satcombo.sdr.SourceFiPoste(rate, "usb", fiInverse, ferme = ferme) }
                .getOrElse { ferme(); throw java.io.IOException("$rate Hz : la FI n'y tient pas") }.also { boite[0] = it }
        }
        if (CodecReseau.de(s.icomCodec) != CodecReseau.PCM16_16K)
            throw java.io.IOException("FI par le réseau : choisir le son « PCM 16 bits, 16 kHz » (Réglages › Poste) ; à 8 kHz la FI ne tient pas")
        val a: (ShortArray, Int, Int) -> Unit = { pcm, n, rate -> if (rate == 16_000) boite[0]?.pousse(pcm, n) }
        val src = fr.f4ioz.satcombo.sdr.SourceFiPoste(16_000, "reseau", fiInverse, ferme = {
            poste.auditeursBruts -= a
            poste.rends(PosteIcomReseau.Usage.FI)
        })
        boite[0] = src
        poste.auditeursBruts += a
        poste.prends(PosteIcomReseau.Usage.FI)?.let { poste.auditeursBruts -= a; throw java.io.IOException(it) }
        return src
    }

    @Synchronized private fun ecrisTrace(ligne: String) {
        runCatching {
            dossier.mkdirs()
            // Kept small: a trace is for reading, 5 MB is plenty.
            if (fichierTrace.length() > 5_000_000) fichierTrace.renameTo(File(dossier, "icom-reseau-trace.1.txt"))
            fichierTrace.appendText(heure.format(Date()) + " " + ligne + "\n")
        }
    }

    fun json(): JSONObject {
        val st = poste.statut()
        val o = JSONObject()
            .put("liaison", s.icomLiaison).put("hote", s.icomHote)
            .put("formatPcm", poste.session?.formatPcm?.let { (if (it.sur) "" else "? ") + it.format.name.lowercase() } ?: "")
            .put("sonFi", poste.session?.formatPcm?.fi == true)
            // The choice AF / IF, and the rig's sound looking like AF while IF is expected.
            .put("fiChoisie", sonFi).put("sonAf", poste.session?.formatPcm?.af == true)
            .put("aigus", poste.session?.formatPcm?.rapportAigus?.let { Math.round(it * 100) / 100.0 } ?: 0.0)
            .put("fiTransport", fiTransport).put("fiCarte", fiCarte).put("fiInverse", fiInverse).put("fi", poste.utilise(PosteIcomReseau.Usage.FI))
            .put("portControle", s.icomPortControle).put("portCiv", s.icomPortCiv).put("portAudio", s.icomPortAudio)
            .put("utilisateur", s.icomUtilisateur).put("mdp", s.icomMdp.isNotEmpty())
            .put("utilisateurCode", s.icomUtilisateurCode.isNotEmpty()).put("mdpCode", s.icomMdpCode.isNotEmpty())
            .put("codec", s.icomCodec).put("latence", s.icomLatenceMs).put("journal", s.icomJournal).put("spectre", s.icomSpectre)
            .put("codecs", JSONArray(CodecReseau.entries.map { JSONObject().put("id", it.id).put("libelle", it.libelle) }))
            .put("etat", st.etat.name).put("message", st.message).put("radio", st.radio)
            .put("latenceMs", st.latenceMs).put("audioRecus", st.audioRecus).put("audioPerdus", st.audioPerdus)
            .put("enRetard", st.audioEnRetard).put("manques", st.manques).put("redemandes", st.redemandes)
            .put("cat", poste.utilise(PosteIcomReseau.Usage.CAT)).put("audio", poste.utilise(PosteIcomReseau.Usage.AUDIO))
            .put("refus", poste.dernierRefus).put("reprises", poste.reprises)
            .put("source", PosteIcomReseau.NOM_SOURCE)
            .put("pret", poste.config().first != null).put("manque", poste.config().second)
            .put("trace", if (fichierTrace.exists()) fichierTrace.absolutePath else "")
        return o
    }

    /** The settings changed on the page; the coded forms, when given with the clear ones, are learnt. */
    fun action(chemin: String, q: Map<String, String>): JSONObject {
        when (chemin) {
            "/api/icomreseau/reglage" -> {
                q["liaison"]?.let { s.icomLiaison = it }
                q["hote"]?.let { h ->
                    val t = h.trim()
                    if (t.isNotEmpty() && !Regex("^[A-Za-z0-9.:-]{1,253}$").matches(t)) return JSONObject().put("ok", false).put("erreur", "adresse non reconnue")
                    s.icomHote = t
                }
                fun port(k: String, f: (Int) -> Unit) = q[k]?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }?.let(f)
                port("pc") { s.icomPortControle = it }; port("pv") { s.icomPortCiv = it }; port("pa") { s.icomPortAudio = it }
                q["utilisateur"]?.let { s.icomUtilisateur = it.take(16) }
                q["mdp"]?.takeIf { it.isNotEmpty() }?.let { s.icomMdp = it.take(16) }
                // Like the password: never shown back, so empty keeps it and « - » clears it.
                q["utilisateurCode"]?.takeIf { it.isNotBlank() }?.let { v ->
                    if (v != "-" && CodageIdentifiants.hex(v) == null) return JSONObject().put("ok", false).put("erreur", "identifiant codé : 32 chiffres hexadécimaux au plus")
                    s.icomUtilisateurCode = if (v == "-") "" else v.filter { !it.isWhitespace() }
                }
                q["mdpCode"]?.takeIf { it.isNotEmpty() }?.let { v ->
                    if (v != "-" && CodageIdentifiants.hex(v) == null) return JSONObject().put("ok", false).put("erreur", "mot de passe codé : 32 chiffres hexadécimaux au plus")
                    s.icomMdpCode = if (v == "-") "" else v.filter { !it.isWhitespace() }
                }
                q["codec"]?.let { c -> if (CodecReseau.entries.any { it.id == c }) s.icomCodec = c }
                q["latence"]?.toIntOrNull()?.let { s.icomLatenceMs = it }
                q["journal"]?.let { s.icomJournal = it == "1" }
                q["sonFi"]?.let { v ->
                    sonFi = v == "1"
                    // IF now: the network's AF source running would feed noise to the decoders.
                    if (sonFi && son.etat.value.appareil == PosteIcomReseau.NOM_SOURCE) son.arrete()
                }
                q["fiTransport"]?.let { fiTransport = it }
                q["fiCarte"]?.let { fiCarte = it }
                q["fiInverse"]?.let { fiInverse = it == "1" }
                q["spectre"]?.let { v ->
                    s.icomSpectre = v == "1"
                    poste.spectreActif(v == "1")
                }
                apprends()
            }
            else -> return JSONObject().put("ok", false)
        }
        return json().put("ok", true)
    }

    /** Clear and coded forms both known: each character at each place goes into the table. */
    private fun apprends() {
        val t = CodageIdentifiants().apply { lis(s.icomTableCodage) }
        var change = false
        CodageIdentifiants.hex(s.icomUtilisateurCode)?.let { if (s.icomUtilisateur.isNotEmpty() && t.apprends(s.icomUtilisateur, it)) change = true }
        CodageIdentifiants.hex(s.icomMdpCode)?.let { if (s.icomMdp.isNotEmpty() && t.apprends(s.icomMdp, it)) change = true }
        if (change) s.icomTableCodage = t.ecrit()
    }

    /**
     * The coding learnt from the owner's capture, held in memory only and
     * wiped after. The answer says what was learnt, never a coded byte nor a
     * clear character; nothing of it goes to the packet log.
     */
    fun apprendsTrace(trace: ByteArray): JSONObject {
        try {
            val b = ApprentissageTrace.apprends(trace, s.icomPortControle, s.icomUtilisateur, s.icomMdp, s.icomTableCodage)
            b.aGarder()?.let { (u, m, t) -> s.icomUtilisateurCode = u; s.icomMdpCode = m; s.icomTableCodage = t }
            val o = JSONObject().put("ok", b.ok).put("connexions", b.connexions)
            if (b.connexions > 0) o.put("reponse", when (b.reponse) {
                    ApprentissageTrace.Reponse.ACCEPTEE -> "acceptée"
                    ApprentissageTrace.Reponse.REFUSEE -> "refusée"
                    ApprentissageTrace.Reponse.INCONNUE -> "réponse du poste non reconnue"
                })
            if (b.ok) o.put("appris", b.appris).put("manquantes", JSONArray(b.placesManquantes)).put("message", b.message)
            else o.put("erreur", b.message)
            return o
        } catch (e: Exception) {
            // The reason only: an exception's text could quote the file.
            return JSONObject().put("ok", false).put("erreur", "trace illisible")
        } finally {
            trace.fill(0)
        }
    }

    /** The rig's scope, last sweep, levels 0…255. */
    fun spectre(): JSONObject {
        val b = poste.spectre.dernier ?: return JSONObject().put("actif", s.icomSpectre).put("points", JSONArray())
        return JSONObject().put("actif", s.icomSpectre).put("bas", b.basHz).put("haut", b.hautHz).put("hors", b.horsBande)
            .put("sub", b.sub).put("ms", b.ms).put("n", poste.spectre.balayages)
            .put("points", JSONArray(b.points.map { Math.round(it * 255) }))
    }
}
