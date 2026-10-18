/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.demo.PagePc
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * What SatMe-PC serves without a session: the login page, and what makes
 * the page an installable application (its manifest, its icons, a service
 * worker keeping only the page's frame).
 */
object PagesAccesPc {

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** The login page: [message] under the field (an error, a wait), [defini] false when no password is set yet. */
    fun connexion(message: String, suite: String, defini: Boolean): String = """<!doctype html>
<html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>SatMe-PC — connexion</title>
<meta name="theme-color" content="#0F161D">
<link rel="manifest" href="/manifest.webmanifest">
<link rel="icon" href="/icone-192.png">
<style>
${PagePc.STYLE_JETONS}
  * { box-sizing:border-box; }
  body { margin:0; min-height:100vh; display:grid; place-items:center; padding:16px; background:var(--s0); color:var(--texte);
    font:var(--fs-m)/1.45 system-ui,-apple-system,'Segoe UI',Roboto,'Noto Sans',sans-serif; -webkit-font-smoothing:antialiased; }
  .boite { width:min(380px,100%); padding:22px 22px 18px; border-radius:12px; background:var(--s2); border:1px solid var(--ligne); box-shadow:var(--ombre); }
  .tete { display:flex; align-items:center; gap:12px; margin-bottom:16px; }
  .tete img { width:44px; height:44px; border-radius:11px; }
  h1 { margin:0; font-size:18px; font-weight:800; letter-spacing:.4px; } h1 span { color:var(--rx); }
  .sous { color:var(--texte-2); font-size:var(--fs-s); }
  label { display:block; color:var(--texte-2); font-size:var(--fs-s); margin:0 0 6px; }
  input { width:100%; padding:9px 11px; border-radius:var(--r2); border:1px solid var(--ligne-forte); background:var(--creux); color:var(--texte); font-size:15px; }
  input:focus { outline:none; border-color:var(--rx); box-shadow:0 0 0 3px rgba(63,224,200,.18); }
  button { width:100%; margin-top:12px; padding:9px 12px; border-radius:var(--r2); border:1px solid var(--rx); background:var(--rx); color:var(--s0); font-weight:700; font-size:var(--fs-l); cursor:pointer; }
  button:hover { filter:brightness(1.08); }
  .msg { min-height:18px; margin-top:10px; font-size:var(--fs-s); color:#FF8A8D; }
  .note { margin-top:14px; padding-top:10px; border-top:1px solid var(--ligne); color:var(--texte-3); font-size:var(--fs-xs); }
  .attente { padding:10px 12px; border-radius:var(--r2); background:rgba(255,180,84,.1); border:1px solid rgba(255,180,84,.45); color:var(--texte); }
</style></head><body>
<main class="boite">
  <div class="tete"><img src="/icone-192.png" alt=""><div><h1>SatMe-<span>PC</span></h1><div class="sous">Accès à distance</div></div></div>
""" + (if (defini) """  <form method="post" action="/connexion">
    <input type="hidden" name="suite" value="${esc(suite)}">
    <label for="mdp">Mot de passe</label>
    <input id="mdp" name="mdp" type="password" autocomplete="current-password" autofocus required maxlength="200">
    <button type="submit">Se connecter</button>
    <div class="msg" role="alert">${esc(message)}</div>
  </form>
  <div class="note">Cette station est pilotée depuis une autre machine du réseau. Sur le PC lui-même, la page s’ouvre sans mot de passe.</div>
""" else """  <div class="attente"><b>Mot de passe à définir sur le PC</b><br>
    <span class="sous">Sur le PC de la station, ouvrir SatMe-PC puis Réglages › Accès à distance, et y choisir un mot de passe.</span></div>
""") + """</main></body></html>
"""

    /** The application's description: its name, its colours (the page's tokens), its icons. */
    const val MANIFESTE = """{
  "name": "SatMe-PC",
  "short_name": "SatMe-PC",
  "description": "Station satellite radioamateur",
  "lang": "fr",
  "start_url": "/",
  "scope": "/",
  "display": "standalone",
  "background_color": "#0A0F14",
  "theme_color": "#0F161D",
  "icons": [
    { "src": "/icone-192.png", "sizes": "192x192", "type": "image/png", "purpose": "any" },
    { "src": "/icone-512.png", "sizes": "512x512", "type": "image/png", "purpose": "any" }
  ]
}
"""

    /**
     * The service worker: the page's frame and its icons kept for when the
     * PC does not answer; everything else (the API, the streams, the sound,
     * the files) left to the network, never stored.
     */
    const val SERVICE_WORKER = """// SatMe-PC: only the page's frame and icons are kept; the API is never cached.
const CACHE = 'satme-pc-v1';
const CADRE = ['/', '/manifest.webmanifest', '/icone-192.png', '/icone-512.png'];
self.addEventListener('install', e => { e.waitUntil(caches.open(CACHE).then(c => c.addAll(CADRE.slice(1))).then(() => self.skipWaiting())); });
self.addEventListener('activate', e => { e.waitUntil(caches.keys().then(l => Promise.all(l.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim())); });
self.addEventListener('fetch', e => {
  const r = e.request;
  if (r.method !== 'GET') return;
  const u = new URL(r.url);
  if (u.origin !== location.origin || !CADRE.includes(u.pathname)) return;
  // The network first: the page as it is now; the copy only when the PC does not answer.
  e.respondWith(fetch(r).then(rep => {
    if (rep.ok && rep.type === 'basic' && !rep.redirected) { const c = rep.clone(); caches.open(CACHE).then(k => k.put(r, c)); }
    return rep;
  }).catch(() => caches.match(r).then(m => m || Response.error())));
});
"""

    private val icones = HashMap<Int, ByteArray?>()

    /** The app's icon at [cote] pixels (PNG), from the launcher icon. */
    @Synchronized fun icone(cote: Int): ByteArray? = icones.getOrPut(cote) {
        val src = MarquePc.icone ?: return@getOrPut null
        runCatching {
            val im = BufferedImage(cote, cote, BufferedImage.TYPE_INT_ARGB)
            val g = im.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(src, 0, 0, cote, cote, null)
            g.dispose()
            java.io.ByteArrayOutputStream().also { ImageIO.write(im, "png", it) }.toByteArray()
        }.getOrNull()
    }
}
