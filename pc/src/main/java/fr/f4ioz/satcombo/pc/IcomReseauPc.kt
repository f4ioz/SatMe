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
                val a: (ShortArray, Int, Int) -> Unit = { pcm, n, rate -> distribue(pcm, n, rate) }
                ecouteur = a
                poste.auditeurs += a
                poste.prends(PosteIcomReseau.Usage.AUDIO)?.also { poste.auditeurs -= a }
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
