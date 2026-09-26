/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One kept QRV picture. Only the ORIGINAL shot is stored: the overlay (locator,
 * callsign, polar plot…) is redrawn every time the picture is opened, so
 * changing an option or fixing a typo in the callsign does not mean losing the
 * photo and shooting it again.
 */
data class QrvPhoto(
    val id: Long,                 // capture time, also the file stem
    val locator: String = "",
    val callsign: String = "",
    val satName: String = "",
    /**
     * Catalogue number of that satellite, 0 when unknown (pictures kept before
     * v16.3 only had the name). Without it, reopening the picture named a
     * satellite the page could not identify — and the pass combo box, which is
     * built from the catalogue number, simply disappeared.
     */
    val satCat: Int = 0,
    val latDeg: Double = 0.0,
    val lonDeg: Double = 0.0,
    /** Pass arc (az, el) sampled AOS→LOS, so the polar plot survives too. */
    val track: List<Pair<Double, Double>> = emptyList(),
    /** AOS of the pass being worked (epoch ms, 0 = unknown). */
    val passMs: Long = 0L,
    val passElDeg: Int = 0
)

/**
 * Keeps the QRV pictures in the app's private storage (filesDir/qrv) instead of
 * the cache: a cache clean-up, or simply Android reclaiming space, used to wipe
 * them. The index lives in qrv/index.json, newest first.
 */
class QrvPhotoStore(context: Context) {

    private val dir = File(context.filesDir, "qrv").apply { mkdirs() }
    private val index = File(dir, "index.json")

    fun fileOf(id: Long): File = File(dir, "qrv_$id.jpg")

    fun load(): List<QrvPhoto> {
        if (!index.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(index.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val id = o.getLong("id")
                if (!fileOf(id).exists()) return@mapNotNull null
                val tr = o.optJSONArray("tr")
                QrvPhoto(
                    id = id,
                    locator = o.optString("loc"),
                    callsign = o.optString("cs"),
                    satName = o.optString("sat"),
                    satCat = o.optInt("cat", 0),
                    latDeg = o.optDouble("lat", 0.0),
                    lonDeg = o.optDouble("lon", 0.0),
                    track = if (tr == null) emptyList() else (0 until tr.length() step 2).map {
                        tr.getDouble(it) to tr.getDouble(it + 1)
                    },
                    passMs = o.optLong("pms", 0L),
                    passElDeg = o.optInt("pel", 0)
                )
            }.sortedByDescending { it.id }
        }.getOrDefault(emptyList())
    }

    private fun save(list: List<QrvPhoto>) {
        val arr = JSONArray()
        list.sortedByDescending { it.id }.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id); put("loc", p.locator); put("cs", p.callsign)
                put("sat", p.satName); put("cat", p.satCat)
                put("lat", p.latDeg); put("lon", p.lonDeg)
                put("pms", p.passMs); put("pel", p.passElDeg)
                // Flat [az, el, az, el…] array: half the JSON of a list of pairs.
                put("tr", JSONArray().apply {
                    p.track.forEach { (az, el) -> put(az); put(el) }
                })
            })
        }
        runCatching { index.writeText(arr.toString()) }
    }

    /** Writes [bmp] (the untouched shot) and adds it at the top of the album. */
    fun add(bmp: Bitmap, meta: QrvPhoto): List<QrvPhoto> {
        runCatching {
            fileOf(meta.id).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        }.getOrElse { return load() }
        val list = (listOf(meta) + load().filter { it.id != meta.id }).take(MAX_KEPT)
        // Anything pushed out of the album loses its file too, otherwise the
        // private storage would grow forever.
        load().filter { old -> list.none { it.id == old.id } }.forEach { runCatching { fileOf(it.id).delete() } }
        save(list)
        return list
    }

    /**
     * Re-reads a kept picture; null if the file vanished.
     *
     * Decoded under the same ceiling as a fresh capture, and retried smaller on
     * failure: a full-size decode fits on a recent handset and runs out of heap
     * on an older one. When it did, the page came back blank with only the
     * satellite name left over — which is how a picture could look "lost".
     */
    fun bitmapOf(id: Long): Bitmap? {
        val f = fileOf(id)
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(f.absolutePath, bounds) }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest > 0 && longest / sample > MAX_EDGE) sample *= 2
        // Four attempts, each half the size of the one before: even a phone with
        // very little heap left ends up with a picture rather than nothing.
        repeat(4) {
            val bmp = runCatching {
                BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                })
            }.getOrNull()
            if (bmp != null) return bmp
            sample *= 2
        }
        return null
    }

    fun delete(id: Long): List<QrvPhoto> {
        runCatching { fileOf(id).delete() }
        val list = load().filter { it.id != id }
        save(list)
        return list
    }

    /** Update the metadata of a kept picture (satellite, callsign, track…). */
    fun update(meta: QrvPhoto): List<QrvPhoto> {
        val list = load().map { if (it.id == meta.id) meta else it }
        save(list)
        return list
    }

    private companion object {
        const val MAX_KEPT = 60
        /** Same ceiling as a fresh capture: the overlay is redrawn on this. */
        const val MAX_EDGE = 2560
    }
}
