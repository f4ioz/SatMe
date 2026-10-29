/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

import fr.f4ioz.satcombo.aprs.KissDecodeur
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * SSDV pictures put together over several passes: a satellite often sends
 * the same picture again (or the rest of it) an orbit or a day later, and
 * one pass rarely brings every packet.
 *
 * - a packet joins the picture of the same callsign and image number last
 *   added to within [FENETRE_MS], when its size and quality match; the
 *   image number is one byte and comes round again, so a packet whose number
 *   is already there with other bytes, or one past a finished picture,
 *   starts a new one;
 * - the same packet twice (two stations, a replay, both bit readings) is
 *   kept once; every packet's CRC was checked by [Ssdv.lis];
 * - each packet remembers the pass that brought it ([Passage]: a gap of
 *   [NOUVEAU_PASSAGE_MS], or another source, begins a new one);
 * - with a [dossier], each picture's packets are written as they come
 *   (`telemesure/ssdv/paquets/<id>.jsonl`, one line a packet, read back
 *   at start); a finished picture is written once as a JPEG next to the
 *   other pictures (`telemesure/ssdv/<id>.jpg`). Test pictures ([Photo.essai])
 *   stay in memory.
 */
class AssemblageSsdv(private val dossier: File?, private val horloge: () -> Long = System::currentTimeMillis) {

    /** One pass's share of a picture. */
    class Passage(val debut: Long, var fin: Long, val source: String, val satellite: String) {
        var paquets = 0; internal set
    }

    class Photo internal constructor(val id: String, val indicatif: String, val numero: Int, val debut: Long, val essai: Boolean) {
        internal val image = Ssdv.Image(indicatif, numero)
        val passages = ArrayList<Passage>()
        /** Packet number → its pass, an index in [passages]. */
        internal val origine = java.util.TreeMap<Int, Int>()
        var satellite = ""; internal set
        var miseAJour = debut; internal set
        /** The finished picture's JPEG, by its file name next to the others; null while not finished. */
        var fichier: String? = null; internal set

        val recus: Int get() = image.recus
        val largeur: Int get() = image.largeur
        val hauteur: Int get() = image.hauteur
        /** The packet count, once the last packet came. */
        val total: Int? get() = image.dernier?.plus(1)
        val manquants: List<Int> get() = image.manquants()
        val complete: Boolean get() = image.dernier != null && image.manquants().isEmpty()

        /** The packet count: known, or guessed from how far the MCUs went (true), or the highest number seen. */
        fun totalEstime(): Pair<Int, Boolean> {
            total?.let { return it to false }
            val n = image.listeNumeros()
            val haut = (n.maxOrNull() ?: -1) + 1
            val (m, mcus) = image.avancementMcu() ?: return haut to true
            if (m <= 0) return haut to true
            // The packet holding that MCU, and the share of the picture it reached.
            val p = n.lastOrNull { image.paquet(it)?.premiereMcu == m } ?: return haut to true
            return maxOf(haut, Math.ceil((p + 1).toDouble() * mcus / (m + 1)).toInt()) to true
        }

        /** Per cent of the packets, of the known or guessed count. */
        fun pourcent(): Int {
            val (t, _) = totalEstime()
            return if (t <= 0) 0 else minOf(100, recus * 100 / t)
        }

        /** The packets by pass, as runs over 0 until the count: start, length, pass index (−1: missing). */
        fun bande(): List<Triple<Int, Int, Int>> {
            val (t, _) = totalEstime()
            val o = synchronized(origine) { HashMap(origine) }
            val l = ArrayList<Triple<Int, Int, Int>>()
            var debut = 0
            var cour = if (t > 0) o[0] ?: -1 else -1
            for (k in 1..t) {
                val v = if (k < t) o[k] ?: -1 else Int.MIN_VALUE
                if (v != cour) { l += Triple(debut, k - debut, cour); debut = k; cour = v }
            }
            return l
        }

        fun jpeg(): ByteArray? = image.jpeg()
    }

    enum class Resultat { NOUVEAU, DOUBLON, REFUSE }

    class Ajout(val photo: Photo?, val resultat: Resultat, /** The packet finished the picture. */ val finie: Boolean = false)

    private val photos = LinkedHashMap<String, Photo>()

    val repertoire: File? get() = dossier?.let { File(it, "telemesure/ssdv/paquets") }
    private val galerie: File? get() = dossier?.let { File(it, "telemesure/ssdv") }

    init { relit() }

    /** The pictures, the latest first. */
    @Synchronized
    fun photos(): List<Photo> = photos.values.sortedByDescending { it.miseAJour }

    @Synchronized
    fun photo(id: String): Photo? = photos[id]

    /** Each new packet kept in a real picture (not a test's): its picture, for the « Publication web ». */
    @Volatile var surAjout: ((Photo) -> Unit)? = null

    /** Adds a packet received at [t] ([source]: « radio », or what it was imported from). */
    @Synchronized
    fun ajoute(p: Ssdv.Paquet, t: Long = horloge(), satellite: String = "", source: String = SOURCE_RADIO, essai: Boolean = false): Ajout {
        if (p.largeur <= 0 || p.hauteur <= 0) return Ajout(null, Resultat.REFUSE)
        val candidat = photos.values.filter { it.indicatif == p.indicatif && it.numero == p.image && it.essai == essai }.maxByOrNull { it.miseAJour }
        var ph: Photo? = null
        if (candidat != null && Math.abs(t - candidat.miseAJour) <= FENETRE_MS && compatible(candidat, p)) {
            val deja = candidat.image.paquet(p.numero)
            val dernier = candidat.image.dernier
            when {
                deja != null && deja.donnees.contentEquals(p.donnees) -> return Ajout(candidat, Resultat.DOUBLON)
                // The same number with other bytes: the image number came round, another picture.
                deja != null -> {}
                // Past the end of a picture: another one.
                dernier != null && p.numero > dernier -> {}
                else -> ph = candidat
            }
        }
        val photo = ph ?: nouvelle(p, t, essai)
        val avant = photo.complete
        if (!place(photo, p, t, satellite, source)) return Ajout(photo, Resultat.REFUSE)
        if (!essai) ecrit(photo, p, t, satellite, source)
        val finie = !avant && photo.complete
        if (finie && !essai) termine(photo)
        limite()
        if (!essai) runCatching { surAjout?.invoke(photo) }
        return Ajout(photo, Resultat.NOUVEAU, finie)
    }

    private fun compatible(ph: Photo, p: Ssdv.Paquet) = ph.recus == 0 ||
        (ph.largeur == p.largeur * 16 && ph.hauteur == p.hauteur * 16 && ph.image.mode == p.mode && ph.image.qualite == p.qualite)

    private fun nouvelle(p: Ssdv.Paquet, t: Long, essai: Boolean): Photo {
        val base = jour(t) + "-" + nomFichier(p.indicatif) + "-" + p.image + (if (essai) "-essai" else "")
        var id = base; var k = 1
        while (photos.containsKey(id) || repertoire?.let { File(it, "$id.jsonl").exists() } == true) id = base + "-" + (++k)
        return Photo(id, p.indicatif, p.image, t, essai).also { photos[id] = it }
    }

    /** The packet into [ph], its pass found or begun; false when it does not fit. */
    private fun place(ph: Photo, p: Ssdv.Paquet, t: Long, satellite: String, source: String): Boolean {
        if (!ph.image.ajoute(p)) return false
        val dernier = ph.passages.lastOrNull()
        val i = if (dernier != null && dernier.source == source && t - dernier.fin <= NOUVEAU_PASSAGE_MS && dernier.debut - t <= NOUVEAU_PASSAGE_MS) ph.passages.size - 1
            else { ph.passages += Passage(t, t, source, satellite); ph.passages.size - 1 }
        val pa = ph.passages[i]
        pa.fin = maxOf(pa.fin, t); pa.paquets++
        synchronized(ph.origine) { ph.origine[p.numero] = i }
        ph.miseAJour = maxOf(ph.miseAJour, t)
        if (satellite.isNotBlank()) ph.satellite = satellite
        return true
    }

    private fun ecrit(ph: Photo, p: Ssdv.Paquet, t: Long, satellite: String, source: String) {
        val d = repertoire ?: return
        if (p.brut.size !in Ssdv.TAILLE_MIN..Ssdv.TAILLE) return
        runCatching {
            d.mkdirs()
            val o = JSONObject().put("t", t).put("src", source).put("sat", satellite).put("hex", hex(p.brut))
            File(d, ph.id + ".jsonl").appendText(o.toString() + "\n")
        }
    }

    /** A finished picture's JPEG, with the others. */
    private fun termine(ph: Photo) {
        val g = galerie ?: return
        runCatching {
            val j = ph.jpeg() ?: return
            g.mkdirs()
            val nom = ph.id + ".jpg"
            File(g, nom).writeBytes(j)
            ph.fichier = nom
        }
    }

    /** Pictures kept in memory: the finished and oldest go first (their files stay). */
    private fun limite() {
        while (photos.size > MAX_PHOTOS) {
            val v = photos.values.filter { it.complete }.minByOrNull { it.miseAJour } ?: photos.values.minByOrNull { it.miseAJour } ?: return
            photos.remove(v.id)
        }
    }

    /** A picture dropped: out of the list, its packets' file removed (a finished picture's JPEG stays). */
    @Synchronized
    fun abandonne(id: String): Boolean {
        val ph = photos.remove(id) ?: return false
        repertoire?.let { File(it, ph.id + ".jsonl").delete() }
        return true
    }

    /** Test pictures forgotten (the « Effacer » of the test). */
    @Synchronized
    fun videEssais() { photos.values.removeAll { it.essai } }

    /** The pictures under way read back from [repertoire] (the latest [MAX_PHOTOS]). */
    @Synchronized
    fun relit() {
        val d = repertoire ?: return
        val fichiers = d.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }.orEmpty().sortedByDescending { it.lastModified() }.take(MAX_PHOTOS)
        for (f in fichiers) runCatching {
            val id = f.name.removeSuffix(".jsonl")
            var ph: Photo? = null
            f.forEachLine(Charsets.UTF_8) { ligne ->
                val o = runCatching { JSONObject(ligne) }.getOrNull() ?: return@forEachLine
                val b = deHex(o.optString("hex")) ?: return@forEachLine
                // The CRC checked again: a damaged line is left out.
                val p = (if (b.size == Ssdv.TAILLE) Ssdv.lis(b) else Ssdv.lisCourt(b, 0, b.size)) ?: return@forEachLine
                val t = o.optLong("t")
                val x = ph ?: Photo(id, p.indicatif, p.image, t, false).also { ph = it }
                if (x.indicatif != p.indicatif || x.numero != p.image) return@forEachLine
                place(x, p, t, o.optString("sat"), o.optString("src", SOURCE_RADIO))
            }
            ph?.let { x ->
                if (x.complete) x.fichier = "$id.jpg".takeIf { n -> galerie?.let { File(it, n).isFile } == true }
                photos[id] = x
            }
        }
    }

    // ---------------------------------------------------------------- import

    /**
     * Packets from a file: SatMe's frame log (JSON lines with « hex », and
     * their time), a KISS file, or raw SSDV packets one after the other.
     * Returns the new packets and the duplicates.
     */
    fun importe(octets: ByteArray, nom: String, satellite: String = ""): Pair<Int, Int> {
        val source = "import " + nom.substringAfterLast('/').substringAfterLast('\\').take(60)
        val maintenant = horloge()
        var nouveaux = 0; var doublons = 0
        fun prend(b: ByteArray, t: Long, sat: String) {
            for (p in Ssdv.cherche(b)) when (ajoute(p, t, sat.ifBlank { satellite }, source).resultat) {
                Resultat.NOUVEAU -> nouveaux++
                Resultat.DOUBLON -> doublons++
                Resultat.REFUSE -> {}
            }
        }
        val debut = octets.firstOrNull()?.toInt()?.and(0xFF)
        when (debut) {
            '{'.code -> String(octets, Charsets.UTF_8).lineSequence().forEach { l ->
                val o = runCatching { JSONObject(l) }.getOrNull() ?: return@forEach
                val b = deHex(o.optString("hex")) ?: return@forEach
                prend(b, o.optLong("t").takeIf { it > 0 } ?: maintenant, o.optString("sat"))
            }
            fr.f4ioz.satcombo.aprs.Kiss.FEND -> KissDecodeur { tr -> prend(tr, maintenant, "") }.octets(octets)
            else -> prend(octets, maintenant, "")
        }
        return nouveaux to doublons
    }

    // ---------------------------------------------------------------- for the page

    @Synchronized
    fun json(max: Int = 24): JSONArray = JSONArray(photos().take(max).map { json(it) })

    @Synchronized
    fun json(ph: Photo): JSONObject {
        val (t, estime) = ph.totalEstime()
        return JSONObject().put("cle", ph.id).put("indicatif", ph.indicatif).put("numero", ph.numero).put("recus", ph.recus)
            .put("total", t).put("estime", estime).put("pourcent", ph.pourcent()).put("manquants", if (estime) maxOf(0, t - ph.recus) else ph.manquants.size)
            .put("dernier", ph.image.dernier ?: -1).put("largeur", ph.largeur).put("hauteur", ph.hauteur).put("t", ph.miseAJour).put("debut", ph.debut)
            .put("essai", ph.essai).put("complete", ph.complete).put("fichier", ph.fichier ?: "").put("satellite", ph.satellite)
            .put("passages", JSONArray(ph.passages.map { JSONObject().put("debut", it.debut).put("fin", it.fin).put("source", it.source).put("sat", it.satellite).put("n", it.paquets) }))
            .put("bande", JSONArray(ph.bande().map { (a, n, i) -> JSONArray().put(a).put(n).put(i) }))
    }

    companion object {
        /** A picture is looked for again within this long of its last packet: two days of passes, well short of 256 pictures. */
        const val FENETRE_MS = 3 * 86_400_000L
        const val NOUVEAU_PASSAGE_MS = 20 * 60_000L
        const val MAX_PHOTOS = 80
        const val SOURCE_RADIO = "radio"

        private fun jour(ms: Long) = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))

        fun nomFichier(s: String) = s.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').take(40).ifEmpty { "ssdv" }

        fun hex(b: ByteArray): String = b.joinToString("") { String.format("%02X", it.toInt() and 0xFF) }

        fun deHex(s: String): ByteArray? {
            val c = s.filter { !it.isWhitespace() }
            if (c.isEmpty() || c.length % 2 != 0) return null
            return runCatching { ByteArray(c.length / 2) { c.substring(2 * it, 2 * it + 2).toInt(16).toByte() } }.getOrNull()
        }

        /** A picture's name that a request may carry. */
        fun idValide(id: String) = Regex("[A-Za-z0-9_-]{1,80}").matches(id)
    }
}
