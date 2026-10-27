/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.securite

import fr.f4ioz.site.http.Reponse

/**
 * The headers every answer carries, and who is really asking.
 *
 * The pages' scripts run under a Content-Security-Policy: the content pages
 * (articles, logbook, galleries…) with a nonce per answer; SatMe's tabs
 * (Orbites, Sked), whose markup carries inline handlers, with 'unsafe-inline'
 * but nothing from elsewhere; a published HTML report sandboxed whole. No
 * page is framed by another site; nothing asks for the camera, the
 * microphone or a payment; the position only for the Grid's « ma position ».
 */
object Securite {

    fun csp(r: Reponse, nonce: String): String {
        val base = "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'self'; form-action 'self'; " +
            "img-src 'self' data: blob:; font-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; media-src 'self' blob:; worker-src 'self' blob:; manifest-src 'self'"
        return when (r.csp) {
            Reponse.Csp.CONTENU -> "$base; script-src 'self' 'nonce-$nonce'; frame-src 'none'"
            // Orbites' ISS live: YouTube's player framed, its probe of the Internet, a direct video the visitor chooses.
            Reponse.Csp.APPLI -> if (r.youtube) base.replace("connect-src 'self'", "connect-src 'self' https://www.youtube-nocookie.com")
                .replace("media-src 'self' blob:", "media-src 'self' blob: https:") +
                "; script-src 'self' 'unsafe-inline'; frame-src https://www.youtube-nocookie.com https://www.youtube.com"
                else "$base; script-src 'self' 'unsafe-inline'; frame-src 'none'"
            // A report published by SatMe: shown, never run; its own styles and pictures only.
            Reponse.Csp.AUCUN -> "sandbox; default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'; font-src data:; frame-ancestors 'self'"
        }
    }

    /** The headers set on every answer (a route's own win). */
    fun entetes(r: Reponse, nonce: String, https: Boolean): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        m["Content-Security-Policy"] = csp(r, nonce)
        m["X-Content-Type-Options"] = "nosniff"
        m["X-Frame-Options"] = "SAMEORIGIN"
        m["Referrer-Policy"] = "strict-origin-when-cross-origin"
        m["Permissions-Policy"] = "geolocation=(self), camera=(), microphone=(), payment=(), usb=(), serial=(), bluetooth=(), " +
            "magnetometer=(), gyroscope=(), accelerometer=(), interest-cohort=(), browsing-topics=()"
        m["Cross-Origin-Opener-Policy"] = "same-origin"
        m["Cross-Origin-Resource-Policy"] = "same-origin"
        if (https) m["Strict-Transport-Security"] = "max-age=31536000"
        return m
    }

    /**
     * The visitor's address: the socket's, or — when the socket is one of [proxies] — the last
     * address of X-Forwarded-For that is not a proxy (nginx appends the one it saw; what the
     * visitor wrote before it is not believed).
     */
    fun ipClient(distante: String, xff: String?, proxies: Set<String>): String {
        val d = normalise(distante)
        if (d !in proxies || xff.isNullOrBlank()) return d
        val chaine = xff.split(',').map { normalise(it.trim()) }.filter { it.isNotEmpty() }
        return chaine.lastOrNull { it !in proxies } ?: d
    }

    /** HTTPS when the trusted proxy says so (X-Forwarded-Proto: https). */
    fun https(distante: String, proto: String?, proxies: Set<String>): Boolean =
        normalise(distante) in proxies && proto?.trim()?.lowercase()?.substringBefore(',') == "https"

    private fun normalise(ip: String): String = ip.trim().removePrefix("/").let {
        // "[::1]:port" or "1.2.3.4" (no port in X-Forwarded-For; the socket's address comes without one).
        if (it.startsWith("[")) it.substringAfter('[').substringBefore(']') else it
    }.take(64)
}
