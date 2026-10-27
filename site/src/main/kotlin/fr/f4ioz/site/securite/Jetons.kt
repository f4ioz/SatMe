/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.securite

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The site's secrets, never kept as they are: the publication token by its
 * SHA-256 (a long random token needs nothing slower), the admin password by
 * PBKDF2-HMAC-SHA256 with its own salt. Every comparison takes the same
 * time whatever matches.
 */
object Jetons {

    private val aleatoire = SecureRandom()

    fun sha256Hex(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    fun sha256Hex(s: String): String = sha256Hex(s.toByteArray(Charsets.UTF_8))

    /** A new random token: 32 bytes, base64url without padding (43 characters). */
    fun nouveau(octets: Int = 32): String {
        val b = ByteArray(octets); aleatoire.nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    /** [jeton] is the one whose SHA-256 is [attenduHex]; false when none is set. Constant time. */
    fun verifieJeton(jeton: String?, attenduHex: String): Boolean {
        if (attenduHex.isBlank()) return false
        val a = sha256Hex(jeton.orEmpty()).toByteArray(Charsets.US_ASCII)
        val b = attenduHex.trim().lowercase().toByteArray(Charsets.US_ASCII)
        return MessageDigest.isEqual(a, b) && !jeton.isNullOrEmpty()
    }

    /** The bearer token of an `Authorization: Bearer …` header. */
    fun porteur(entete: String?): String? {
        val e = entete?.trim() ?: return null
        if (!e.startsWith("Bearer ", ignoreCase = true)) return null
        return e.substring(7).trim().takeIf { it.isNotEmpty() && it.length <= 512 }
    }

    private const val ITERATIONS = 210_000

    /** [mdp] hashed for `site.conf`: `pbkdf2$iterations$salt$hash` (base64). */
    fun hacheMotDePasse(mdp: String, iterations: Int = ITERATIONS): String {
        val sel = ByteArray(16); aleatoire.nextBytes(sel)
        val h = pbkdf2(mdp, sel, iterations)
        val b64 = Base64.getEncoder()
        return "pbkdf2$" + iterations + "$" + b64.encodeToString(sel) + "$" + b64.encodeToString(h)
    }

    /** [mdp] against [stocke] (`pbkdf2$…`); false when [stocke] is empty or not understood. */
    fun verifieMotDePasse(mdp: String, stocke: String): Boolean {
        val p = stocke.trim().split('$')
        if (p.size != 4 || p[0] != "pbkdf2") return false
        val it = p[1].toIntOrNull()?.takeIf { it in 10_000..5_000_000 } ?: return false
        val sel = runCatching { Base64.getDecoder().decode(p[2]) }.getOrNull() ?: return false
        val attendu = runCatching { Base64.getDecoder().decode(p[3]) }.getOrNull() ?: return false
        if (mdp.length > 1024) return false
        return MessageDigest.isEqual(pbkdf2(mdp, sel, it, attendu.size * 8), attendu)
    }

    private fun pbkdf2(mdp: String, sel: ByteArray, iterations: Int, bits: Int = 256): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(mdp.toCharArray(), sel, iterations, bits)).encoded
}
