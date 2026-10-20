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
import fr.f4ioz.satcombo.domain.Doppler
import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.espace.MeteoSpatiale
import fr.f4ioz.satcombo.domain.RapportPassage
import fr.f4ioz.satcombo.domain.RapportPassage.Evenement
import fr.f4ioz.satcombo.domain.RapportPassage.Fichier
import fr.f4ioz.satcombo.domain.SpectrePassage
import fr.f4ioz.satcombo.phonie.BullePhonie
import fr.f4ioz.satcombo.sdr.CascadeIq
import fr.f4ioz.satcombo.sdr.SigMF
import fr.f4ioz.satcombo.station.Montage
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/**
 * A pass's observation report on the PC (the journal's « Rapport »): what
 * [RapportPassage] writes, gathered from the data folder — the pass, its
 * contacts, pictures, APRS frames and recordings ([JournalPc]); the I/Q
 * recordings, the Doppler logs, the detailed journal's spectra and decodes,
 * the telemetry frames, the voice written down, the Montage, the levels
 * and the space weather when they are there. Read only.
 */
class RapportPc(
    private val journal: JournalPc,
    private val station: StationPc,
    private val dossier: File,
    /** The SDR's channel level kept lately (time, dB), when it ran. */
    private val niveaux: () -> List<Pair<Long, Double>> = { emptyList() },
    /** Space weather's Kp values, when its copies hold some. */
    private val kp: () -> List<MeteoSpatiale.Kp> = { emptyList() },
    /** FT8/FT4 heard lately (kept in memory): (slot, text, details). */
    private val ft8: () -> List<Triple<Long, String, String>> = { emptyList() },
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    val dossierSpectres = File(dossier, "journal/spectres")
    private val dossierIq = File(dossier, "enregistrements-iq")

    /** How the report's links are made: the PC's page, the single file, the ZIP. */
    enum class Forme { PAGE, FICHIER, PAQUET }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** The I/Q recordings overlapping [e]: (data file, its meta). */
    fun iq(e: JournalPassage.Entree): List<Pair<File, SigMF.Meta>> =
        (dossierIq.listFiles { f -> f.name.endsWith(SigMF.EXT_DATA) } ?: emptyArray()).mapNotNull { f ->
            val m = SigMF.meta(f).takeIf { it.isFile }?.let { runCatching { SigMF.litMeta(it.readText()) }.getOrNull() } ?: return@mapNotNull null
            if (m.essai) return@mapNotNull null
            val d = m.debutMs ?: return@mapNotNull null
            val fin = d + Math.round(f.length() / m.format.octetsParPaire / m.debit * 1000)
            val sat = m.norad == e.catnum || m.satellite.isBlank() || JournalPassage.memeSatellite(m.satellite, e.satName)
            if (sat && d <= e.finMs && fin >= e.debutMs) f to m else null
        }.sortedBy { it.second.debutMs }

    /**
     * The waterfall: from the I/Q recording covering most of the pass
     * (worked out once, kept beside the spectra), else the detailed
     * journal's lines.
     */
    fun cascade(e: JournalPassage.Entree): SpectrePassage.Bande? {
        val i = iq(e).maxByOrNull { (f, m) -> minOf(e.finMs, (m.debutMs ?: 0L) + (f.length() / m.format.octetsParPaire / m.debit * 1000).toLong()) - maxOf(e.debutMs, m.debutMs ?: 0L) }
        if (i != null) runCatching {
            val (f, m) = i
            val garde = File(dossierSpectres, "iq_" + f.name.removeSuffix(SigMF.EXT_DATA) + SpectrePassage.EXT)
            if (garde.isFile && garde.lastModified() >= f.lastModified()) return garde.inputStream().buffered().use { SpectrePassage.lit(it) }
            val b = CascadeIq.calcule(f, m, SpectreJournalPc.LARGEUR_HZ, SpectreJournalPc.BINS, decalageHz = dopplerFixe(e, m))
            if (b.lignes.size >= 2) {
                dossierSpectres.mkdirs()
                garde.outputStream().buffered().use { SpectrePassage.ecrit(b, it) }
                return b
            }
        }.onFailure { System.err.println("rapport : cascade I/Q de ${e.id} : $it") }
        val l = SpectrePassage.duPassage(dossierSpectres, e.catnum, e.debutMs, e.finMs)
            .mapNotNull { f -> runCatching { f.inputStream().buffered().use { SpectrePassage.lit(it) } }.getOrNull() }
        if (l.isEmpty()) return null
        return SpectrePassage.Bande(e.catnum, e.satName, l.first().largeurHz, l.flatMap { it.lignes }.sortedBy { it.tMs })
    }

    /**
     * A recording made at one fixed frequency: the Doppler's drift from its
     * start, worked out from the elements (the receiver did not follow it).
     */
    private fun dopplerFixe(e: JournalPassage.Entree, m: SigMF.Meta): ((Long) -> Double)? {
        if (m.captures.size > 1) return null
        val ciel = m.captures.firstOrNull()?.frequenceHz?.takeIf { it > 0 } ?: return null
        val sat = station.etat.value.satellites.firstOrNull { it.catalogNumber == e.catnum } ?: return null
        val obs = fr.f4ioz.satcombo.location.Maidenhead.centre(e.locator)?.let { fr.f4ioz.satcombo.data.Observer(it.first, it.second) }
            ?: station.etat.value.observateur ?: return null
        val t0 = m.debutMs ?: return null
        val pred = fr.f4ioz.satcombo.domain.PassPredictor()
        val ref = runCatching { pred.rangeRates(sat, obs, listOf(t0)).first() }.getOrNull() ?: return null
        val cache = HashMap<Long, Double>()
        return { t ->
            cache.getOrPut(t / 1000) {
                val rr = runCatching { pred.rangeRates(sat, obs, listOf(t)).first() }.getOrDefault(ref)
                (Doppler.downlink(ciel, rr) - Doppler.downlink(ciel, ref)).toDouble()
            }
        }
    }

    /** The station as the Montage describes it: its rigs, receivers, antennas. */
    fun materiel(): List<RapportPassage.Materiel> = runCatching {
        val c = Montage.lit(File(dossier, "montage.json").takeIf { it.isFile }?.readText() ?: return emptyList())
        val ordre = listOf(Montage.Genre.POSTE to "Poste", Montage.Genre.SDR to "SDR", Montage.Genre.RECEPTEUR_DATV to "Récepteur DATV",
            Montage.Genre.ANTENNE to "Antenne", Montage.Genre.LNB to "LNB", Montage.Genre.CONVERTISSEUR to "Convertisseur",
            Montage.Genre.AMPLI to "Ampli", Montage.Genre.ROTOR to "Rotor")
        ordre.flatMap { (g, nom) -> c.elements.filter { Montage.modele(it)?.genre == g }.map { RapportPassage.Materiel(nom, Montage.nom(it)) } }
    }.getOrDefault(emptyList())

    /** Everything known of pass [e], its links made for [forme]. */
    fun rapport(e: JournalPassage.Entree, forme: Forme, cle: String = ""): RapportPassage.Rapport {
        val l = journal.liens(e)
        val f = JournalPassage.fenetre(e, 60_000L)
        val s = station.settings
        val evts = ArrayList<Evenement>()
        // APRS: the frames heard on the pass.
        l.trames.distinctBy { it.source to it.quand / 5_000 }.forEach { p ->
            evts += Evenement(p.quand, "APRS", p.source + if (p.viaIss) " (via ISS)" else "",
                (p.message?.let { m -> (p.destinataire?.let { "→ $it : " } ?: "") + m } ?: p.commentaire).trim().take(90))
        }
        // The detailed journal's decodes (CW, FT8), then FT8 still in memory.
        val gardes = (dossierSpectres.listFiles { x -> x.name.endsWith(".decodes.jsonl") && x.name.startsWith("${e.catnum}_") } ?: emptyArray())
            .flatMap { SpectreJournalPc.decodes(it) }.filter { it.tMs in f }
        evts += gardes
        val vusFt8 = gardes.filter { it.genre == "FT8" }.map { it.tMs to it.texte }.toSet()
        ft8().filter { it.first in f && (it.first to it.second) !in vusFt8 }.forEach { evts += Evenement(it.first, "FT8", it.second, it.third) }
        // The voice written down, a short excerpt.
        evts += phonie(e, f).take(12)
        // Telemetry frames.
        val (tlm, tlmOk) = telemesure(e, f)
        val sons = journal.morceaux(e).withIndex().map { (i, m) ->
            Fichier(m.f.name, m.f.length(), when (forme) {
                Forme.PAGE -> "son?id=" + enc(e.id) + "&n=$i&cle=" + enc(cle); Forme.PAQUET -> "sons/" + m.f.name; Forme.FICHIER -> null
            }, m.origine, m.dureeMs)
        }
        val iqs = iq(e).map { (d, _) ->
            Fichier(d.name, d.length(), when (forme) {
                Forme.PAGE -> "../sdr-iq/" + enc(d.name).replace("+", "%20") + "?cle=" + enc(cle); Forme.PAQUET -> "iq/" + SigMF.meta(d).name; Forme.FICHIER -> null
            })
        }
        val dop = station.dossierDoppler?.let { fr.f4ioz.satcombo.domain.JournalDoppler.duPassage(it, e.satName, e.debutMs, e.finMs) }.orEmpty().map { d ->
            Fichier(d.name, d.length(), when (forme) {
                Forme.PAGE -> "doppler?nom=" + enc(d.name) + "&cle=" + enc(cle); Forme.PAQUET -> "doppler/" + d.name; Forme.FICHIER -> null
            })
        }
        // The level: the rig's S-meter when kept, else the SDR's channel.
        val (niv, unite, source) = if (e.signal.isNotEmpty()) Triple(e.signal.map { RapportPassage.Niveau(it.tMs, it.s.toDouble()) }, "S", "S-mètre du poste")
            else Triple(niveaux().filter { it.first in f }.map { RapportPassage.Niveau(it.first, it.second) }, "dB", "niveau du canal SDR")
        val milieu = (e.debutMs + e.finMs) / 2
        val k = runCatching { kp() }.getOrDefault(emptyList()).filter { it.nature != "prevu" || it.t > horloge() }
            .minByOrNull { Math.abs(it.t - milieu) }?.takeIf { Math.abs(it.t - milieu) <= 3 * 3_600_000L }
        return RapportPassage.Rapport(
            id = e.id, satellite = e.satName, catnum = e.catnum, debutMs = e.debutMs, finMs = e.finMs,
            locator = e.locator, indicatif = s.callsign, transpondeur = e.transpondeur,
            points = e.points.map { RapportPassage.Point(it.tMs, it.az, it.el) },
            station = materiel(), kp = k?.kp, kpMs = k?.t,
            niveaux = niv, niveauUnite = unite, niveauSource = source,
            cascade = runCatching { cascade(e) }.getOrNull(),
            evenements = evts.sortedBy { it.tMs },
            images = l.images.filter { (png, _) -> png.name !in e.masquees }.mapNotNull { (png, shot) ->
                vignette(png)?.let { RapportPassage.Image(shot.timeMs, shot.mode.ifBlank { "SSTV" }, "image/jpeg", it, png.name) } },
            contacts = l.qsos.map { RapportPassage.Contact(it.timeMs, it.callsign, it.theirLocator, it.rstSent, it.rstRcvd, it.mode, it.nom) },
            tramesTelemesure = tlm, tramesTelemesureValides = tlmOk,
            sons = sons, iq = iqs, doppler = dop,
            reconstitue = e.reconstitue, faitMs = horloge(), logiciel = "SatMe-PC " + versionPc())
    }

    /** The overs written down during the pass, for its satellite (or none named). */
    private fun phonie(e: JournalPassage.Entree, f: LongRange): List<Evenement> {
        val d = File(dossier, "phonie")
        val jours = (f.first / 86_400_000L..f.last / 86_400_000L).map {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(it * 86_400_000L))
        }
        return jours.flatMap { j ->
            val x = File(d, "$j.jsonl").takeIf { it.isFile } ?: return@flatMap emptyList()
            runCatching { x.readLines(Charsets.UTF_8) }.getOrDefault(emptyList()).mapNotNull { runCatching { BullePhonie.depuis(JSONObject(it)) }.getOrNull() }
        }.filter { it.t in f && (it.satellite.isBlank() || JournalPassage.memeSatellite(it.satellite, e.satName)) && it.texte.isNotBlank() }
            .map { b -> Evenement(b.t, "PHONIE", b.texte.trim().take(200), listOf(b.langue, b.traduction.take(120)).filter { it.isNotBlank() }.joinToString(" · ")) }
    }

    /** Telemetry frames of the pass's satellite: how many, how many valid. */
    private fun telemesure(e: JournalPassage.Entree, f: LongRange): Pair<Int, Int> {
        val l = (File(dossier, "telemesure").listFiles { x -> x.name.endsWith(".jsonl") } ?: emptyArray()).flatMap { x ->
            runCatching { x.readLines() }.getOrDefault(emptyList()).mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        }.filter { it.optLong("t") in f && JournalPassage.memeSatellite(it.optString("satellite"), e.satName) }
        return l.size to l.count { it.optBoolean("valide") }
    }

    /** An SSTV picture, small (320 px wide JPEG). */
    private fun vignette(f: File): ByteArray? = runCatching {
        val img = javax.imageio.ImageIO.read(f) ?: return null
        val w = minOf(320, img.width); val h = maxOf(1, img.height * w / img.width)
        val v = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = v.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(img, 0, 0, w, h, null); g.dispose()
        val o = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(v, "jpg", o)
        o.toByteArray()
    }.getOrNull()

    // ------------------------------------------------------------ the routes (under /journal/rapport)

    private fun barre(e: JournalPassage.Entree, cle: String): String {
        val q = "id=" + enc(e.id) + "&cle=" + enc(cle)
        return "<div class=\"barre\"><a class=\"b\" href=\"../journal\">← Journal</a><b>Rapport du passage</b>" +
            "<a class=\"b\" href=\"rapport/html?$q\" download>HTML</a><a class=\"b\" href=\"rapport/zip?$q\" download>ZIP</a>" +
            "<button id=\"rp-partage\" onclick=\"rpPartage()\">Partager</button><button onclick=\"print()\">Imprimer</button>" +
            "<span class=\"msg\" id=\"rp-msg\"></span></div>" +
            "<script>async function rpPartage(){const m=document.getElementById('rp-msg');m.textContent='…';" +
            "try{const r=await fetch('rapport/html?$q');const b=await r.blob();" +
            "const n=(r.headers.get('Content-Disposition')||'').split('filename=')[1]||'rapport.html';const nom=n.replace(/\"/g,'');" +
            "const f=new File([b],nom,{type:'text/html'});" +
            "if(navigator.canShare&&navigator.canShare({files:[f]})){await navigator.share({files:[f],title:document.title});m.textContent='';return;}" +
            "const a=document.createElement('a');a.href=URL.createObjectURL(b);a.download=nom;document.body.appendChild(a);a.click();a.remove();" +
            "m.textContent='Fichier HTML enregistré : '+nom+' (rien n\\'est envoyé en ligne)';}catch(x){m.textContent=String(x);}}</script>"
    }

    /** [route] after `/rapport`: the page, the single HTML file, the ZIP, the waterfall alone. */
    fun sert(route: String, p: (String) -> String): ReponseWeb {
        val e = journal.passage(p("id")) ?: return ReponseWeb("404 Not Found", "text/plain; charset=utf-8", "passage inconnu".toByteArray())
        val cle = p("cle")
        return when (route) {
            "", "/" -> {
                val r = rapport(e, Forme.PAGE, cle)
                ReponseWeb("200 OK", "text/html; charset=utf-8", RapportPassage.html(r, RapportPassage.Liens(barre(e, cle))).toByteArray(Charsets.UTF_8))
            }
            "/html" -> {
                val r = rapport(e, Forme.FICHIER)
                ReponseWeb("200 OK", "text/html; charset=utf-8", RapportPassage.html(r, RapportPassage.Liens(lecteurs = false)).toByteArray(Charsets.UTF_8),
                    "Content-Disposition: attachment; filename=\"${RapportPassage.nomFichier(r, "html")}\"\r\n")
            }
            "/zip" -> {
                val f = paquet(e)
                ReponseWeb("200 OK", "application/zip", fichier = f, entetes = "Content-Disposition: attachment; filename=\"${f.name}\"\r\n")
            }
            "/cascade.png" -> {
                val b = cascade(e)?.takeIf { it.lignes.size >= 2 } ?: return ReponseWeb("404 Not Found", "text/plain; charset=utf-8", "pas de cascade".toByteArray())
                ReponseWeb("200 OK", "image/png", RapportPassage.pngCascade(b))
            }
            else -> ReponseWeb("404 Not Found", "text/plain; charset=utf-8", "inconnu".toByteArray())
        }
    }

    /** The ZIP, written to `exports/`: the report, its sounds, pictures, logs, the I/Q metadata, the waterfall. */
    fun paquet(e: JournalPassage.Entree): File {
        val r = rapport(e, Forme.PAQUET)
        val png = r.cascade?.takeIf { it.lignes.size >= 2 }?.let { RapportPassage.pngCascade(it) }
        val html = RapportPassage.html(r, RapportPassage.Liens(), png)
        val fichiers = ArrayList<Pair<String, File>>()
        journal.morceaux(e).forEach { fichiers += "sons/" + it.f.name to it.f }
        journal.liens(e).images.forEach { (f, _) -> if (f.name !in e.masquees) fichiers += "sstv/" + f.name to f }
        station.dossierDoppler?.let { fr.f4ioz.satcombo.domain.JournalDoppler.duPassage(it, e.satName, e.debutMs, e.finMs) }.orEmpty().forEach { fichiers += "doppler/" + it.name to it }
        iq(e).forEach { (d, _) -> fichiers += "iq/" + SigMF.meta(d).name to SigMF.meta(d) }
        val sortie = File(dossier, "exports").apply { mkdirs() }
        val f = File(sortie, RapportPassage.nomFichier(r, "zip"))
        val tmp = File(sortie, f.name + ".part")
        tmp.outputStream().buffered().use { RapportPassage.zip(html, fichiers, it, listOfNotNull(png?.let { "cascade.png" to it })) }
        tmp.renameTo(f)
        return f
    }
}

