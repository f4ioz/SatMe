/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site

import fr.f4ioz.site.http.Requete
import fr.f4ioz.site.securite.Jetons
import fr.f4ioz.site.securite.Limiteur
import fr.f4ioz.site.securite.Securite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecuriteTest {

    @Test fun `the token is checked against its hash, never accepted empty`() {
        val j = Jetons.nouveau()
        val h = Jetons.sha256Hex(j)
        assertEquals(43, j.length)
        assertTrue(Jetons.verifieJeton(j, h))
        assertTrue(Jetons.verifieJeton(j, h.uppercase()))
        assertFalse(Jetons.verifieJeton(j + "x", h))
        assertFalse(Jetons.verifieJeton("", Jetons.sha256Hex("")))
        assertFalse(Jetons.verifieJeton(null, h))
        assertFalse(Jetons.verifieJeton(j, ""))
    }

    @Test fun `the bearer is read from its header only`() {
        assertEquals("abc", Jetons.porteur("Bearer abc"))
        assertEquals("abc", Jetons.porteur("bearer   abc "))
        assertNull(Jetons.porteur("Basic abc"))
        assertNull(Jetons.porteur("Bearer "))
        assertNull(Jetons.porteur(null))
        assertNull(Jetons.porteur("Bearer " + "x".repeat(600)))
    }

    @Test fun `the admin password is hashed with its own salt`() {
        val a = Jetons.hacheMotDePasse("un mot de passe", 20_000)
        val b = Jetons.hacheMotDePasse("un mot de passe", 20_000)
        assertTrue(a.startsWith("pbkdf2\$20000\$"))
        assertFalse(a == b)
        assertTrue(Jetons.verifieMotDePasse("un mot de passe", a))
        assertFalse(Jetons.verifieMotDePasse("un mot de pass", a))
        assertFalse(Jetons.verifieMotDePasse("un mot de passe", ""))
        assertFalse(Jetons.verifieMotDePasse("x", "pbkdf2\$abc\$def\$ghi"))
        // Too few iterations: not a hash this site made.
        assertFalse(Jetons.verifieMotDePasse("un mot de passe", Jetons.hacheMotDePasse("un mot de passe", 1000)))
    }

    @Test fun `paths that leave the site are refused`() {
        assertEquals("/media/abc/fichier.png", Requete.cheminNet("/media/abc/fichier.png"))
        assertEquals("/articles/2026/été", Requete.cheminNet("/articles/2026/%C3%A9t%C3%A9"))
        for (p in listOf("/media/../etc/passwd", "/media/%2e%2e/x", "/%2E%2E/x", "//evil", "/a\\b", "/a%5Cb", "/a%00b", "/./x", "x", "/" + "a".repeat(600)))
            assertNull(p, Requete.cheminNet(p))
    }

    @Test fun `X-Forwarded-For is believed from the proxy only`() {
        val proxies = setOf("127.0.0.1")
        assertEquals("203.0.113.9", Securite.ipClient("127.0.0.1", "203.0.113.9", proxies))
        // What the visitor wrote before nginx's own is not believed.
        assertEquals("203.0.113.9", Securite.ipClient("127.0.0.1", "1.2.3.4, 203.0.113.9", proxies))
        assertEquals("198.51.100.7", Securite.ipClient("198.51.100.7", "1.2.3.4", proxies))
        assertTrue(Securite.https("127.0.0.1", "https", proxies))
        assertFalse(Securite.https("198.51.100.7", "https", proxies))
    }

    @Test fun `the brake counts per key in its window`() {
        var t = 0L
        val l = Limiteur(3, 1000L) { t }
        repeat(3) { assertTrue(l.accepte("a")) }
        assertFalse(l.accepte("a"))
        assertTrue(l.accepte("b"))
        t = 1001L
        assertTrue(l.accepte("a"))
    }
}
