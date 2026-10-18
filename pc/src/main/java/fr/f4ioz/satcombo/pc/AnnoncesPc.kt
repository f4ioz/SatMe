/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.annonces.Annonces
import fr.f4ioz.satcombo.annonces.DetecteurAnnonces
import fr.f4ioz.satcombo.annonces.FileAnnonces
import fr.f4ioz.satcombo.plateforme.Stockage
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Spoken announcements on SatMe-PC: the station looked at once a second
 * ([tic]), what changed said aloud — by the browser showing the page (the
 * live stream carries each sentence), or by this PC's own speech engine
 * (speech-dispatcher, eSpeak NG, eSpeak, Piper), the SDR speaker lowered
 * meanwhile. Off unless chosen; nothing leaves this PC.
 */
class AnnoncesPc(
    private val stockage: Stockage,
    private val lit: () -> DetecteurAnnonces.Instantane?,
    private val moi: () -> String,
    private val lanceur: Lanceur = Lanceur.Systeme,
    private val trouve: (String) -> String? = OutilsPc::trouve,
    private val horloge: () -> Long = System::currentTimeMillis,
    /** The SDR speaker's level (1 normal), lowered while this PC speaks. */
    private val gainSdr: (Float) -> Unit = {},
    /** The speaking thread started (tests drive [ditProchaine] themselves). */
    demarreParole: Boolean = true,
) {
    /** Runs a speech command, waits for it; its exit code. */
    fun interface Lanceur {
        fun lance(cmd: List<String>, entree: String?): Int

        companion object {
            val Systeme = Lanceur { cmd, entree ->
                val p = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                runCatching { p.outputStream.use { o -> if (entree != null) o.write(entree.toByteArray(Charsets.UTF_8)) } }
                if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); -1 } else p.exitValue()
            }
        }
    }

    /** A speech engine found on this PC. */
    data class Moteur(val nom: String, val chemin: String, val libelle: String)

    @Volatile var reglages: Annonces.Reglages = Annonces.lit(stockage); private set
    val file = FileAnnonces(horloge)
    private val detecteur = DetecteurAnnonces()
    private val dites = ArrayDeque<JSONObject>()
    @Volatile var dernierSeq = 0L; private set
    @Volatile var parle = false; private set
    @Volatile var erreur = ""; private set
    private var moteursCache: List<Moteur>? = null
    private var moteursMs = 0L

    init {
        if (demarreParole) Thread({ boucle() }, "satme-annonces").apply { isDaemon = true; start() }
    }

    /** Once a second: what changed, queued when wanted. */
    fun tic() {
        val i = lit() ?: return
        val r = reglages
        for (e in detecteur.compare(i, r.minutesAvant, r.rappelMinutes)) if (r.veut(e.sorte)) file.propose(e.sorte, Annonces.texte(e, r, moi()))
    }

    /** « Tester »: a sentence through the chosen outputs, at once. */
    fun essai(): JSONObject {
        val r = reglages
        if (!r.on) return JSONObject().put("ok", false).put("erreur", "annonces coupées : les activer d'abord")
        file.propose(Annonces.Sorte.ESSAI, Annonces.texte(Annonces.Evenement(Annonces.Sorte.ESSAI), r, moi()))
        return JSONObject().put("ok", true)
    }

    // ---------------------------------------------------------------- speaking

    private fun boucle() {
        while (true) {
            val dit = runCatching { ditProchaine() }.getOrDefault(false)
            if (!dit) Thread.sleep(150)
        }
    }

    /** The next sentence said (by the PC, or handed to the browsers then given its time); false when none. */
    fun ditProchaine(attente: (Long) -> Unit = { Thread.sleep(it) }): Boolean {
        val a = file.prochaine() ?: return false
        val r = reglages
        if (!r.on || r.muet) return true
        val m = if (r.pc()) moteur(r) else null
        val navigateur = r.navigateur() || m == null
        synchronized(dites) {
            dites.addLast(JSONObject().put("seq", a.seq).put("ms", a.ms).put("sorte", a.sorte.name).put("texte", a.texte)
                .put("navigateur", navigateur).put("vitesse", r.vitesse).put("volume", r.volume))
            while (dites.size > 20) dites.removeFirst()
            dernierSeq = a.seq
        }
        if (m != null) {
            parle = true
            val baisse = r.baisse < 100
            if (baisse) gainSdr(r.baisse / 100f)
            try {
                val code = lanceur.lance(commande(m, a.texte, r), if (m.nom == "piper") a.texte else null)
                erreur = if (code == 0) "" else m.libelle + " a échoué (code " + code + ")"
            } catch (x: Exception) {
                erreur = m.libelle + " : " + (x.message ?: "erreur")
            } finally {
                if (baisse) gainSdr(1f)
                parle = false
            }
        } else attente(dureeMs(a.texte, r.vitesse))
        return true
    }

    /** The sentences handed out after [seq], for the live stream. */
    fun depuis(seq: Long): List<JSONObject> = synchronized(dites) { dites.filter { it.getLong("seq") > seq } }

    // ---------------------------------------------------------------- engines

    fun moteurs(): List<Moteur> = synchronized(this) {
        val t = horloge()
        moteursCache?.takeIf { t - moteursMs < 60_000L } ?: MOTEURS.mapNotNull { (n, lib) -> trouve(n)?.let { Moteur(n, it, lib) } }
            // Piper speaks only through aplay, and only with a voice model chosen.
            .filter { it.nom != "piper" || trouve("aplay") != null }
            .also { moteursCache = it; moteursMs = t }
    }

    /** The engine used: the one chosen when present, else the first found (Piper only with its model). */
    fun moteur(r: Annonces.Reglages = reglages): Moteur? {
        val l = moteurs().filter { it.nom != "piper" || r.voix.endsWith(".onnx") }
        return l.firstOrNull { it.nom == r.moteur } ?: l.firstOrNull()
    }

    /** The command saying [texte] (no shell but for Piper's pipe, the text then on its input). */
    fun commande(m: Moteur, texte: String, r: Annonces.Reglages): List<String> {
        val t = texte.trimStart('-', ' ')
        val vol = r.volume.coerceIn(0, 100)
        return when (m.nom) {
            "spd-say" -> listOf(m.chemin, "-w", "-l", "fr", "-r", ((r.vitesse - 1.0) * 100).toInt().coerceIn(-100, 100).toString(),
                "-i", (vol * 2 - 100).toString()) + (if (r.voix.isNotBlank()) listOf("-y", r.voix) else emptyList()) + listOf("--", t)
            "espeak-ng", "espeak" -> listOf(m.chemin, "-v", if (r.voix.isNotBlank()) r.voix else "fr", "-s", (175 * r.vitesse).toInt().coerceIn(80, 450).toString(),
                "-a", (vol * 2).toString(), "--", t)
            else -> {
                val aplay = trouve("aplay") ?: "aplay"
                listOf("/bin/sh", "-c", "\"\$1\" --model \"\$2\" --length_scale \"\$3\" --output_raw | \"\$4\" -q -r 22050 -f S16_LE -t raw -",
                    "piper", m.chemin, r.voix, String.format(java.util.Locale.ROOT, "%.2f", 1.0 / r.vitesse), aplay)
            }
        }
    }

    // ---------------------------------------------------------------- the page

    fun json(): JSONObject {
        val r = reglages
        val ms = moteurs()
        val m = moteur(r)
        return JSONObject().put("ok", true).put("on", r.on).put("muet", r.muet)
            .put("sortes", JSONArray(Annonces.Sorte.values().filter { it != Annonces.Sorte.ESSAI }.map {
                JSONObject().put("id", it.name).put("nom", it.libelle).put("on", it in r.sortes) }))
            .put("detail", r.detail.name).put("sortie", r.sortie.name).put("phonetique", r.phonetique)
            .put("minutes", r.minutesAvant).put("rappel", r.rappelMinutes).put("vitesse", r.vitesse).put("volume", r.volume)
            .put("voix", r.voix).put("moteur", r.moteur).put("baisse", r.baisse)
            .put("moteurs", JSONArray(ms.map { JSONObject().put("nom", it.nom).put("libelle", it.libelle) }))
            .put("moteurEffectif", m?.nom ?: "").put("parle", parle).put("erreur", erreur)
            .put("attente", file.taille()).put("repetees", file.repetees).put("debordees", file.debordees)
            .put("dites", JSONArray(synchronized(dites) { dites.toList().takeLast(6).reversed() }))
            .put("seq", dernierSeq)
    }

    /** The card's settings (POST); the answer is the state. */
    fun regle(q: Map<String, String>): JSONObject {
        var r = reglages
        fun b(k: String) = q[k]?.let { it == "1" }
        b("on")?.let { r = r.copy(on = it); if (!it) file.vide() }
        b("muet")?.let { r = r.copy(muet = it); if (it) file.vide() }
        b("phonetique")?.let { r = r.copy(phonetique = it) }
        q["detail"]?.let { d -> Annonces.Detail.values().firstOrNull { it.name == d } }?.let { r = r.copy(detail = it) }
        q["sortie"]?.let { d -> Annonces.Sortie.values().firstOrNull { it.name == d } }?.let { r = r.copy(sortie = it) }
        q["minutes"]?.toIntOrNull()?.let { r = r.copy(minutesAvant = it.coerceIn(1, 30)) }
        q["rappel"]?.toIntOrNull()?.let { r = r.copy(rappelMinutes = it.coerceIn(1, 60)) }
        q["vitesse"]?.replace(',', '.')?.toDoubleOrNull()?.let { r = r.copy(vitesse = it.coerceIn(0.5, 2.0)) }
        q["volume"]?.toIntOrNull()?.let { r = r.copy(volume = it.coerceIn(0, 100)) }
        q["baisse"]?.toIntOrNull()?.let { r = r.copy(baisse = it.coerceIn(0, 100)) }
        q["voix"]?.let { r = r.copy(voix = it.trim().take(300)) }
        q["moteur"]?.let { n -> if (n.isEmpty() || MOTEURS.any { it.first == n }) r = r.copy(moteur = n) }
        for (s in Annonces.Sorte.values()) b("s_" + s.name)?.let { on -> r = r.copy(sortes = if (on) r.sortes + s else r.sortes - s) }
        reglages = r
        Annonces.ecrit(stockage, r)
        return json()
    }

    companion object {
        val MOTEURS = listOf("spd-say" to "speech-dispatcher", "espeak-ng" to "eSpeak NG", "espeak" to "eSpeak", "piper" to "Piper")

        /** How long a browser takes to say it: about 14 characters a second at normal speed. */
        fun dureeMs(texte: String, vitesse: Double): Long = (600 + texte.length * 70 / vitesse).toLong()

        /** CW text grown since [avant]: the part new in [apres] (cleared or cut at its front: found again by its tail). */
        fun nouveauCw(avant: String, apres: String): String = when {
            apres == avant -> ""
            apres.startsWith(avant) -> apres.substring(avant.length)
            else -> {
                val queue = avant.takeLast(40)
                val i = if (queue.length >= 8) apres.lastIndexOf(queue) else -1
                if (i >= 0) apres.substring(i + queue.length) else apres
            }
        }
    }

    /**
     * Who called us in CW: followed between two looks, each call told once
     * (the text read again each second holds the old ones).
     */
    class SuiviCw {
        private var vu = ""
        private var n = 0L
        fun nouveaux(texte: String, moi: String): List<DetecteurAnnonces.Decode> {
            val neuf = nouveauCw(vu, texte)
            val fenetre = vu.takeLast(60)
            vu = texte
            if (neuf.isEmpty()) return emptyList()
            val qui = DetecteurAnnonces.appelCw(fenetre + neuf, moi) ?: return emptyList()
            // Only a call whose end is in the new part (else it was heard last time).
            if (DetecteurAnnonces.appelCw(fenetre, moi) == qui && !neuf.contains(qui)) return emptyList()
            return listOf(DetecteurAnnonces.Decode("cw:" + (++n) + ":" + qui, qui, moi, "CW"))
        }
    }
}
