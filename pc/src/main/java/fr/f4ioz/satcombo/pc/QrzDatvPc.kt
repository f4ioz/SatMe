/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.datv.QrzDatv
import fr.f4ioz.satcombo.domain.QrzReponse
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * QRZ.com records of the stations seen in DATV, looked up in the background
 * as the DATV tab polls (nothing while it is closed), kept on disk 7 days
 * (`qrz-datv.json` in [dossier]), and their photos proxied for the page.
 * [compte]: QRZ credentials given; [cherche]: one lookup (null: no account
 * or network); [telecharge]: one photo, at most [QrzDatv.PHOTO_MAX] bytes.
 */
class QrzDatvPc(
    private val dossier: File,
    private val compte: () -> Boolean,
    private val cherche: (String) -> QrzReponse.Fiche?,
    private val telecharge: (String) -> ByteArray? = ::telechargePhoto,
    private val horloge: () -> Long = System::currentTimeMillis,
    /** Runs the lookup: a background thread, or at once in the tests. */
    private val lance: (Runnable) -> Unit = Executors.newSingleThreadExecutor { r -> Thread(r, "qrz-datv").apply { isDaemon = true } }::execute,
) {
    private val cache = QrzDatv.Cache(horloge)
    private val fichier = File(dossier, "qrz-datv.json")

    private class Photo(val octets: ByteArray, val type: String)
    private val photos = object : LinkedHashMap<String, Photo>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Photo>?) = size > 24
    }
    private val essaisPhoto = HashMap<String, Long>()

    init {
        runCatching { if (fichier.isFile) synchronized(cache) { cache.importe(JSONObject(fichier.readText())) } }
    }

    /** The callsigns shown now: the next one QRZ does not know yet is asked (at the cache's pace). */
    fun suit(candidats: List<String>) {
        if (candidats.isEmpty() || !compte()) return
        val c = synchronized(cache) { cache.prochain(candidats) } ?: return
        lance(Runnable {
            val f = runCatching { cherche(c) }.getOrNull()
            synchronized(cache) { cache.recu(c, f) }
            if (QrzDatv.sorte(f) != QrzDatv.Sorte.ECHEC) enregistre()
        })
    }

    private fun enregistre() {
        runCatching {
            val t = synchronized(cache) { cache.exporte() }.toString()
            dossier.mkdirs()
            val tmp = File(dossier, "qrz-datv.json.tmp")
            tmp.writeText(t); if (!tmp.renameTo(fichier)) { fichier.writeText(t); tmp.delete() }
        }
    }

    fun json(candidats: List<String>, moi: fr.f4ioz.satcombo.data.Observer?): JSONObject =
        synchronized(cache) { QrzDatv.json(cache, candidats, compte(), moi) }

    /** The record found for [brut], or null (not asked yet, not in QRZ, failed). */
    fun fiche(brut: String): QrzReponse.Fiche? = synchronized(cache) {
        cache.entree(brut)?.fiche?.takeIf { QrzDatv.sorte(it) == QrzDatv.Sorte.TROUVE }
    }

    /**
     * The photo of [brut]'s record: only the address QRZ gave, only from its
     * hosts, an image of at most [QrzDatv.PHOTO_MAX] bytes; fetched again at
     * most every 10 minutes after a failure. Bytes and type, or null.
     */
    fun photo(brut: String): Pair<ByteArray, String>? {
        val c = QrzDatv.cle(brut)
        val url = fiche(c)?.image ?: return null
        if (!QrzDatv.photoPermise(url)) return null
        synchronized(photos) {
            photos[c]?.let { return it.octets to it.type }
            val t = horloge()
            essaisPhoto[c]?.let { if (t - it < QrzDatv.PAUSE_INDICATIF_MS) return null }
            essaisPhoto[c] = t
        }
        val b = runCatching { telecharge(url) }.getOrNull()?.takeIf { it.size <= QrzDatv.PHOTO_MAX } ?: return null
        val type = QrzDatv.typeImage(b) ?: return null
        synchronized(photos) { photos[c] = Photo(b, type); essaisPhoto.remove(c) }
        return b to type
    }

    companion object {
        /** One photo from QRZ's hosts: no redirect followed (it could leave them), cut past the limit. */
        fun telechargePhoto(url: String): ByteArray? {
            if (!QrzDatv.photoPermise(url)) return null
            val c = java.net.URI(url.trim()).toURL().openConnection() as java.net.HttpURLConnection
            return try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 8000; c.readTimeout = 10000
                c.setRequestProperty("User-Agent", fr.f4ioz.satcombo.data.AgentHttp.USER_AGENT)
                if (c.responseCode != 200) return null
                if (c.contentLengthLong > QrzDatv.PHOTO_MAX) return null
                c.inputStream.use { i ->
                    val b = i.readNBytes(QrzDatv.PHOTO_MAX + 1)
                    if (b.size > QrzDatv.PHOTO_MAX) null else b
                }
            } finally { runCatching { c.disconnect() } }
        }
    }
}
