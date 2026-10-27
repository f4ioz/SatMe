/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.demo.ReponseWeb
import fr.f4ioz.satcombo.plateforme.Stockage
import fr.f4ioz.satcombo.sdr.AnneauIq
import fr.f4ioz.satcombo.sdr.Dsp
import fr.f4ioz.satcombo.sdr.EnregistrementIq
import fr.f4ioz.satcombo.sdr.SigMF
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

/**
 * The SDR tab's « I/Q »: the raw samples kept and played back.
 *
 * - **Remonter le temps** (off by default): the last 1 to 10 minutes always
 *   kept, in a file of a fixed size in the data folder (a ring on the disk:
 *   the PC's memory is never asked for gigabytes); « Garder » writes them as
 *   a SigMF recording while the ring goes on.
 * - **Enregistrer**: from now on, up to a limit (minutes, and the disk's
 *   room).
 * - **The recordings** (`enregistrements-iq` in the data folder, SigMF or
 *   raw files dropped there): listed, downloaded, deleted, played back as
 *   the receiver (« Fichier I/Q »).
 *
 * A file at the receiver's rate ([Dsp.RTL_RATE]): bytes from a dongle
 * (cu8), 16 bits from the Pluto, the bladeRF or the generator (ci16_le).
 * Receive only: nothing here reaches a transmitter.
 */
class IqPc(
    private val sdr: SdrPc,
    private val station: StationPc,
    dossierDonnees: File,
    private val journal: JournalPc? = null,
    private val stockage: Stockage = station.stockage,
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    val dossier = File(dossierDonnees, "enregistrements-iq")
    private val fichierAnneau = File(File(dossierDonnees, "tmp"), "anneau-iq.bin")
    private val racine = dossierDonnees

    var anneauOn: Boolean
        get() = stockage.getBoolean(CLE_ANNEAU, false)
        private set(v) { stockage.edit().putBoolean(CLE_ANNEAU, v).apply() }
    var minutes: Int
        get() = stockage.getInt(CLE_MINUTES, 2).coerceIn(1, 10)
        private set(v) { stockage.edit().putInt(CLE_MINUTES, v.coerceIn(1, 10)).apply() }
    var limiteMinutes: Int
        get() = stockage.getInt(CLE_LIMITE, 10).coerceIn(1, 120)
        private set(v) { stockage.edit().putInt(CLE_LIMITE, v.coerceIn(1, 120)).apply() }

    @Volatile private var anneau: AnneauIq? = null
    @Volatile private var anneauSeance = -1
    @Volatile private var erreur = ""

    /** The recording under way, and what is known of it at its start. */
    private class EnCours(val e: EnregistrementIq, val base: String, val meta: SigMF.Meta)
    @Volatile private var enCours: EnCours? = null

    /** A copy of the ring under way (0–1), and the last one's outcome. */
    @Volatile private var copie: Float? = null
    @Volatile private var message = ""

    init {
        // Nothing ever left from a previous run: the ring starts empty.
        fichierAnneau.delete()
        sdr.iqBrut = { b8, b16, n, ciel, pll, fine -> bloc(b8, b16, n, ciel, pll, fine) }
    }

    /** A block of the receiver's raw I/Q (on its thread: no waiting here). */
    fun bloc(b8: ByteArray?, b16: ShortArray?, n: Int, ciel: Long, pll: Long, fine: Double) {
        val format = if (b16 != null) SigMF.Format.CI16 else SigMF.Format.CU8
        if (anneauOn) {
            var a = anneau
            if (a == null || a.format != format || anneauSeance != sdr.seance || a.ferme) {
                // A new start (or another receiver): the ring starts again.
                a?.close()
                a = runCatching { AnneauIq(fichierAnneau, format, (minutes * 60L * Dsp.RTL_RATE), horloge = horloge) }
                    .getOrElse { erreur = "anneau impossible : " + (it.message ?: ""); anneauOn = false; null }
                anneau = a; anneauSeance = sdr.seance
            }
            if (a != null) runCatching { if (b16 != null) a.ecrit16(b16, n, ciel, pll, fine) else a.ecrit8(b8!!, n, ciel, pll, fine) }
                .onFailure { erreur = "écriture de l'anneau : " + (it.message ?: ""); anneauOn = false; a.close(); anneau = null }
        }
        val e = enCours ?: return
        val continue_ = if (b16 != null) e.e.ecrit16(b16, n, ciel, pll, fine) else e.e.ecrit8(b8!!, n, ciel, pll, fine)
        if (!continue_) termine()
    }

    /** What is known now of what is received: for a recording's meta. */
    private fun metaMaintenant(format: SigMF.Format, debutMs: Long): SigMF.Meta {
        val e = sdr.etat.value
        val suivi = station.etat.value.suivi
        val sat = if (sdr.generateur != null) "" else e.satellite
        val norad = if (sdr.suitSatellite && suivi != null && sat == suivi.name) suivi.catalogNumber else 0
        val pass = if (norad > 0) station.passageEnCours(norad, debutMs) else null
        val conv = station.convSdrDeFi(e.pllHz)
        return SigMF.Meta(format, Dsp.RTL_RATE.toDouble(), debutMs,
            materiel = e.tuner, enregistreur = "SatMe-PC " + versionPc(), auteur = station.settings.callsign,
            description = if (sdr.generateur != null) "Générateur d'essai de SatMe (aucune antenne)" else "",
            satellite = sat, norad = norad, mode = sdr.modeEffectif(),
            oscillateurHz = if (conv.configure && conv.accepteEnEntree(e.pllHz)) conv.olHz else 0L,
            aosMs = pass?.first ?: 0L, losMs = pass?.second ?: 0L, essai = sdr.generateur != null)
    }

    private fun place(octets: Long): String? {
        val libre = racine.usableSpace
        return if (libre < octets + MARGE_DISQUE) "pas assez de place sur le disque (" + Taille.texte(libre) + " libres, il faut " +
            Taille.texte(octets + MARGE_DISQUE) + ")" else null
    }

    /** Turns the ring on or off, its length in minutes. */
    @Synchronized fun regleAnneau(on: Boolean?, min: Int?): String? {
        min?.let { if (it != minutes) { minutes = it; anneau?.close(); anneau = null } }
        if (on == true && !anneauOn) {
            place(AnneauIq.octets(minutes, SigMF.Format.CI16))?.let { return it }
            erreur = ""
        }
        on?.let { anneauOn = it; if (!it) { anneau?.close(); anneau = null } }
        return null
    }

    /** The ring's last [secondes] (all, at most) written as a recording, on a thread of its own. */
    @Synchronized fun garde(secondes: Int?): String? {
        val a = anneau ?: return "rien de gardé : « Remonter le temps » est éteint, ou le récepteur n'a pas encore tourné"
        if (copie != null) return "une copie est déjà en cours"
        val paires = (secondes?.toLong()?.times(Dsp.RTL_RATE) ?: Long.MAX_VALUE).coerceAtMost(a.tenues)
        place(paires * a.format.octetsParPaire)?.let { return it }
        val meta0 = metaMaintenant(a.format, horloge())
        copie = 0f; message = ""
        thread(isDaemon = true, name = "satme-iq-garde") {
            var data: File? = null
            try {
                dossier.mkdirs()
                val tmp = File(dossier, ".copie-" + horloge() + SigMF.EXT_DATA)
                data = tmp
                val x = FileOutputStream(tmp).use { o -> a.exporte(o, paires) { copie = it } }
                val base = SigMF.nomBase(x.debutMs, meta0.satellite.ifBlank { if (meta0.essai) "essai" else "" }, x.captures.firstOrNull()?.frequenceHz ?: 0L)
                val fin = unique(base)
                if (!tmp.renameTo(File(dossier, fin + SigMF.EXT_DATA))) throw java.io.IOException("renommage impossible")
                File(dossier, fin + SigMF.EXT_META).writeText(SigMF.ecritMeta(meta0.copy(debutMs = x.debutMs, captures = x.captures)))
                message = "gardé : $fin (" + Taille.duree(x.paires / Dsp.RTL_RATE.toDouble()) + ")"
            } catch (e: Exception) {
                data?.delete()
                message = "non gardé : " + (e.message ?: e.javaClass.simpleName)
            } finally { copie = null }
        }
        return null
    }

    private fun unique(base: String): String {
        var b = base; var k = 2
        while (File(dossier, b + SigMF.EXT_DATA).exists() || File(dossier, b + SigMF.EXT_META).exists()) b = base + "-" + k++
        return b
    }

    /** « Enregistrer »: from now, up to [limite] minutes (and the disk's room). */
    @Synchronized fun enregistre(on: Boolean, limite: Int?): String? {
        limite?.let { limiteMinutes = it }
        if (!on) { termine(); return null }
        if (enCours != null) return null
        if (!sdr.etat.value.actif) return "démarrer le récepteur d'abord"
        if (sdr.lecture != null) return "un enregistrement est déjà ce qu'on écoute"
        val format = if (sdr.recepteur == SdrPc.RTL) SigMF.Format.CU8 else SigMF.Format.CI16
        var paires = limiteMinutes * 60L * Dsp.RTL_RATE
        val libre = racine.usableSpace - MARGE_DISQUE
        if (libre < 10L * Dsp.RTL_RATE * format.octetsParPaire) return place(paires * format.octetsParPaire)
        paires = minOf(paires, libre / format.octetsParPaire)
        val t = horloge()
        val meta0 = metaMaintenant(format, t)
        val base = unique(SigMF.nomBase(t, meta0.satellite.ifBlank { if (meta0.essai) "essai" else "" }, sdr.etat.value.cielHz))
        dossier.mkdirs()
        enCours = EnCours(EnregistrementIq(File(dossier, base + SigMF.EXT_DATA), format, paires, horloge = horloge), base, meta0)
        message = ""
        return null
    }

    /** The recording closed, its meta written. */
    @Synchronized private fun termine() {
        val c = enCours ?: return
        enCours = null
        c.e.close()
        if (c.e.paires == 0L) { c.e.data.delete(); message = "rien d'enregistré (le récepteur ne donnait rien)"; return }
        File(dossier, c.base + SigMF.EXT_META).writeText(SigMF.ecritMeta(c.meta.copy(debutMs = c.e.debutMs, captures = c.e.captures)))
        message = (c.e.erreur?.let { "enregistrement arrêté : $it" } ?: "enregistré : ${c.base}") + " (" + Taille.duree(c.e.paires / Dsp.RTL_RATE.toDouble()) + ")"
    }

    /** The receiver stopped: a recording under way ends with it. */
    fun tic() { if (enCours != null && !sdr.etat.value.actif) termine() }

    // ---------------------------------------------------------------- the recordings

    private fun nomSur(nom: String): File? = nom.takeIf { it.matches(Regex("[A-Za-z0-9._-]+")) && !it.startsWith(".") }
        ?.let { File(dossier, it) }?.takeIf { it.isFile }

    /** A file of the folder, to download. */
    fun fichier(nom: String): File? = nomSur(nom)

    private fun liste(): JSONArray {
        val fs = dossier.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }.orEmpty()
        val passages = runCatching { journal?.passages() }.getOrNull().orEmpty()
        val l = fs.mapNotNull { f ->
            val n = f.name
            when {
                n.endsWith(SigMF.EXT_DATA) -> {
                    val mf = SigMF.meta(f)
                    val m = if (mf.isFile) SigMF.litMeta(mf.readText()) else null
                    val o = JSONObject().put("nom", n).put("meta", if (mf.isFile) mf.name else "").put("taille", f.length() + (if (mf.isFile) mf.length() else 0L))
                        .put("sigmf", true).put("date", m?.debutMs ?: f.lastModified())
                    if (m != null) {
                        o.put("format", m.format.sigmf).put("debit", m.debit).put("duree", f.length() / m.format.octetsParPaire / m.debit)
                            .put("frequence", m.captures.firstOrNull()?.frequenceHz ?: 0L).put("satellite", m.satellite).put("mode", m.mode)
                            .put("materiel", m.materiel).put("essai", m.essai)
                        val t = m.debutMs ?: 0L
                        passages.firstOrNull { m.norad > 0 && it.catnum == m.norad && t in (it.debutMs - 120_000L)..(it.finMs + 120_000L) }
                            ?.let { o.put("passage", it.satName + " " + java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.FRANCE).format(java.util.Date(it.debutMs))) }
                    } else if (enCours?.e?.data?.name == n) o.put("enCours", true)
                    else o.put("erreur", if (mf.isFile) "sigmf-meta illisible" else "sigmf-meta absent")
                    o
                }
                SigMF.Format.deExtension(n) != null ->
                    JSONObject().put("nom", n).put("taille", f.length()).put("brut", true).put("format", SigMF.Format.deExtension(n)!!.sigmf).put("date", f.lastModified())
                else -> null
            }
        }
        return JSONArray(l.sortedByDescending { it.optLong("date") })
    }

    fun etat(): JSONObject {
        val a = anneau
        val e = enCours
        val format = if (sdr.recepteur == SdrPc.RTL) SigMF.Format.CU8 else SigMF.Format.CI16
        return JSONObject().put("ok", true).put("dossier", dossier.absolutePath)
            .put("anneau", JSONObject().put("on", anneauOn).put("minutes", minutes)
                .put("octets", AnneauIq.octets(minutes, format)).put("octets8", AnneauIq.octets(minutes, SigMF.Format.CU8))
                .put("octets16", AnneauIq.octets(minutes, SigMF.Format.CI16))
                .put("tenuS", (a?.tenues ?: 0L) / Dsp.RTL_RATE.toDouble()).put("format", a?.format?.sigmf ?: format.sigmf))
            .put("enregistre", JSONObject().put("on", e != null).put("limite", limiteMinutes)
                .put("s", (e?.e?.paires ?: 0L) / Dsp.RTL_RATE.toDouble()).put("octets", (e?.e?.paires ?: 0L) * (e?.e?.format?.octetsParPaire ?: 0))
                .put("nom", e?.base ?: ""))
            .put("copie", copie ?: JSONObject.NULL).put("message", message).put("erreur", erreur)
            .put("libre", racine.usableSpace).put("parMinute8", 60L * Dsp.RTL_RATE * 2).put("parMinute16", 60L * Dsp.RTL_RATE * 4)
            .put("fichiers", liste())
    }

    /** `/api/sdr-iq…`: the state (GET) and the actions (POST). */
    fun sert(chemin: String, methode: String, p: (String) -> String): ReponseWeb {
        fun json(o: JSONObject) = ReponseWeb("200 OK", "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))
        if (chemin == "/api/sdr-iq") return json(etat())
        if (methode != "POST") return ReponseWeb("405 Method Not Allowed", "application/json; charset=utf-8", "{\"ok\":false}".toByteArray())
        val err: String? = runCatching {
            when (chemin) {
                "/api/sdr-iq/anneau" -> regleAnneau(p("on").takeIf { it.isNotEmpty() }?.let { it == "1" }, p("minutes").toIntOrNull())
                "/api/sdr-iq/garde" -> garde(p("s").toIntOrNull())
                "/api/sdr-iq/enregistre" -> enregistre(p("on") == "1", p("limite").toIntOrNull())
                "/api/sdr-iq/supprime" -> {
                    val f = nomSur(p("nom")) ?: return@runCatching "fichier inconnu"
                    if (sdr.lecture?.donnees?.canonicalFile == f.canonicalFile) return@runCatching "en cours de lecture : arrêter d'abord"
                    if (enCours?.e?.data?.canonicalFile == f.canonicalFile) return@runCatching "en cours d'enregistrement"
                    if (f.name.endsWith(SigMF.EXT_DATA)) SigMF.meta(f).delete()
                    f.delete(); null
                }
                "/api/sdr-iq/rejoue" -> {
                    val f = nomSur(p("nom")) ?: return@runCatching "fichier inconnu"
                    if (SigMF.Format.deExtension(f.name) != null) {
                        val d = p("debit").replace(',', '.').toDoubleOrNull() ?: return@runCatching "un fichier brut : donner son débit"
                        sdr.fichierDebit = Math.round(d)
                        sdr.fichierFrequence = p("mhz").replace(',', '.').toDoubleOrNull()?.let { Math.round(it * 1e6) } ?: 0L
                    }
                    termine()
                    sdr.fichierIq = f.absolutePath
                    sdr.recepteur = SdrPc.FICHIER
                    sdr.decalageHz = 0
                    if (!sdr.demarre()) sdr.etat.value.erreur.ifBlank { "lecture impossible" } else null
                }
                "/api/sdr-iq/lecture" -> {
                    val l = sdr.lecture ?: return@runCatching "aucun enregistrement en lecture"
                    p("pause").takeIf { it.isNotEmpty() }?.let { l.enPause = it == "1" }
                    p("vitesse").replace(',', '.').toDoubleOrNull()?.let { l.vitesse = it }
                    p("boucle").takeIf { it.isNotEmpty() }?.let { l.boucle = it == "1" }
                    p("cale").takeIf { it.isNotEmpty() }?.let { l.caleTranches = it == "1"; l.cherche(l.positionS) }
                    p("pos").replace(',', '.').toDoubleOrNull()?.let { l.cherche(it) }
                    null
                }
                "/api/sdr-iq/generateur" -> {
                    sdr.reglagesGenerateur = if (p("defaut") == "1") fr.f4ioz.satcombo.sdr.GenerateurIq.Reglages()
                        else sdr.reglagesGenerateur.avec { k -> p(k).takeIf { it.isNotEmpty() } }
                    null
                }
                else -> "inconnu"
            }
        }.getOrElse { it.message ?: "erreur" }
        return json(if (err == null) etat() else JSONObject().put("ok", false).put("erreur", err))
    }

    /** Sizes and durations as the page says them. */
    object Taille {
        fun texte(o: Long): String = when {
            o >= 1L shl 30 -> String.format(java.util.Locale.FRANCE, "%.1f Go", o / (1L shl 30).toDouble())
            o >= 1L shl 20 -> String.format(java.util.Locale.FRANCE, "%.0f Mo", o / (1L shl 20).toDouble())
            else -> String.format(java.util.Locale.FRANCE, "%.0f ko", o / 1024.0)
        }
        fun duree(s: Double): String = if (s >= 60) "${(s / 60).toInt()} min ${(s % 60).toInt()} s" else String.format(java.util.Locale.FRANCE, "%.1f s", s)
    }

    companion object {
        const val CLE_ANNEAU = "pc_iq_anneau"
        const val CLE_MINUTES = "pc_iq_minutes"
        const val CLE_LIMITE = "pc_iq_limite"
        /** Always left free on the disk. */
        const val MARGE_DISQUE = 2L shl 30
    }
}
