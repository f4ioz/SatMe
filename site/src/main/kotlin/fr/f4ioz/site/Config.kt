/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site

import java.io.File
import java.time.ZoneId
import java.util.Properties

/**
 * The site's settings: `site.conf` (Java properties, UTF-8), kept outside the
 * package and outside Git — it holds the Wavelog key and the hashes of the
 * publication token and of the admin password, never the secrets themselves.
 * Every key has a default but those: an empty one leaves its part off (no
 * Wavelog key: the logbook shows what SatMe published; no token hash: the
 * publication API answers 403; no password hash: no admin page).
 */
data class Config(
    /** The public address, without the final slash: canonical links, sitemap, feeds. */
    val urlBase: String = "https://f4ioz.fr",
    val indicatif: String = "F4IOZ",
    val nom: String = "Olivier",
    val locator: String = "JN18FS",
    val fuseau: ZoneId = ZoneId.of("Europe/Paris"),
    /** Where to listen: the loopback by default, nginx in front. */
    val ecoute: String = "127.0.0.1",
    val port: Int = 8001,
    /** The site's own data: publications, caches (TLE, Wavelog, tiles), sessions. */
    val donnees: File = File("/var/lib/f4ioz-site-kt"),
    /** Articles, pages and photo albums (Markdown and pictures, kept in Git). */
    val contenu: File = File("/var/lib/f4ioz-site-kt/contenu"),
    /** The proxies whose X-Forwarded-For/-Proto/-Host are believed (nginx). */
    val proxies: Set<String> = setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1"),
    val wavelogUrl: String = "",
    val wavelogCle: String = "",
    /** Wavelog station profiles to read (ids); empty: all of them. */
    val wavelogStations: List<String> = emptyList(),
    /** SHA-256 (hex) of the publication token SatMe-PC sends. */
    val jetonSha256: String = "",
    /** The admin password, PBKDF2 (`pbkdf2$iterations$salt$hash`, see `f4ioz-site mot-de-passe`). */
    val adminMotDePasse: String = "",
    /** The SatMe GP server for the orbital elements (AMSAT's bulletin when it does not answer). */
    val tleServeur: String = "https://gp.f4ioz.fr",
    /** The satellites of the home page's passes (names or catalogue numbers). */
    val satellitesAccueil: List<String> = listOf("ISS", "SO-50", "RS-44", "AO-7", "PO-101", "AO-91"),
    /** A journal's video at most (MB). */
    val videoMaxMo: Int = 200,
    /** True: nothing fetched from the Internet (tests, a preview without network). */
    val horsLigne: Boolean = false,
    /** Secure cookies even over plain HTTP (behind a proxy that does not say X-Forwarded-Proto). */
    val cookiesSecurises: Boolean = true,
    val qrz: String = "https://www.qrz.com/db/F4IOZ",
    val github: String = "https://github.com/f4ioz",
    val club: String = "Radioclub F6KMX",
) {
    val urlBaseNette: String get() = urlBase.trimEnd('/')

    companion object {
        /** [f] read; a missing file gives the defaults (a first preview). */
        fun lis(f: File?): Config {
            val p = Properties()
            if (f != null && f.isFile) f.reader(Charsets.UTF_8).use { p.load(it) }
            return depuis(p, f?.absoluteFile?.parentFile)
        }

        fun depuis(p: Properties, dossierConf: File? = null): Config {
            val d = Config()
            fun s(k: String, def: String) = p.getProperty(k)?.trim()?.takeIf { it.isNotEmpty() } ?: def
            fun l(k: String, def: List<String>) = p.getProperty(k)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: def
            fun fichier(k: String, def: File): File = p.getProperty(k)?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { v -> File(v).let { if (it.isAbsolute || dossierConf == null) it else File(dossierConf, v) } } ?: def
            val donnees = fichier("donnees", d.donnees)
            return Config(
                urlBase = s("url_base", d.urlBase).trimEnd('/'),
                indicatif = s("indicatif", d.indicatif).uppercase(),
                nom = s("nom", d.nom),
                locator = s("locator", d.locator).uppercase(),
                fuseau = runCatching { ZoneId.of(s("fuseau", "Europe/Paris")) }.getOrDefault(d.fuseau),
                ecoute = s("ecoute", d.ecoute),
                port = s("port", d.port.toString()).toIntOrNull()?.coerceIn(1, 65535) ?: d.port,
                donnees = donnees,
                contenu = fichier("contenu", File(donnees, "contenu")),
                proxies = l("proxies", d.proxies.toList()).toSet(),
                wavelogUrl = s("wavelog.url", ""),
                wavelogCle = s("wavelog.cle", ""),
                wavelogStations = l("wavelog.stations", emptyList()),
                jetonSha256 = s("publication.jeton_sha256", "").lowercase(),
                adminMotDePasse = s("admin.mot_de_passe", ""),
                tleServeur = s("tle.serveur", d.tleServeur),
                satellitesAccueil = l("satellites.accueil", d.satellitesAccueil),
                videoMaxMo = s("publication.video_max_mo", d.videoMaxMo.toString()).toIntOrNull()?.coerceIn(1, 4000) ?: d.videoMaxMo,
                horsLigne = s("hors_ligne", "false").toBoolean(),
                cookiesSecurises = s("cookies_securises", "true").toBoolean(),
                qrz = s("lien.qrz", d.qrz),
                github = s("lien.github", d.github),
                club = s("club", d.club),
            )
        }
    }
}
