/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import java.io.File

/**
 * The sound cards named as the operator knows them: « IC-9700 (USB Audio
 * CODEC) », « Carte mère (ALC897) », from what Linux says of them in /proc
 * and /sys — never a device opened, never a serial port touched.
 *
 * A rig's codec is recognised by its USB place: an IC-9700 holds a hub with
 * its two serial bridges and its codec; the codec's hub neighbours say whose
 * it is (an Icom's serial number « IC-9700 13012155 A »), or the CAT port's
 * own place does.
 */
object CartesSonLinux {

    /** A card as Linux describes it. [usb]: its USB place ("1-10.1.1.4"), "" when not USB. */
    data class Carte(val numero: Int, val id: String, val usbId: String, val usb: String, val produit: String)

    /** What is known of the rig: its model's name ("IC-9700"), whether it is an Icom, the CAT ports' paths. */
    data class Poste(val nom: String = "", val icom: Boolean = false, val chemins: List<String> = emptyList())

    /** A card's friendly name and, when it is the rig's, the rig's name. */
    data class Libelle(val libelle: String, val poste: String = "")

    private fun lit(f: File): String = runCatching { f.readText().trim() }.getOrDefault("")

    /** The USB place in a sysfs path: its last "bus-port.port…" part. */
    fun placeUsb(chemin: String): String =
        chemin.split('/').lastOrNull { Regex("^\\d+-\\d+(\\.\\d+)*$").matches(it) }.orEmpty()

    /** The hub a USB place hangs from ("1-10.1.1.4" → "1-10.1.1"); "" at a root port. */
    fun parent(place: String): String = if (place.contains('.')) place.substringBeforeLast('.') else ""

    /** The card's number in a Java mixer's name ("CODEC [plughw:1,0]" → 1 to 0). */
    fun numero(nomMixer: String): Pair<Int, Int>? =
        Regex("\\[(?:plug)?hw:(\\d+),(\\d+)]").find(nomMixer)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

    /** An entry that only plays (the sound server's playback client): never a capture source. */
    fun lectureSeule(nomMixer: String): Boolean = nomMixer.startsWith("alsa_playback")

    /** The cards under [racine] (« / » on the PC, a fixture in the tests). */
    fun cartes(racine: File): List<Carte> {
        val asound = File(racine, "proc/asound")
        return (asound.listFiles().orEmpty()).mapNotNull { d ->
            val n = Regex("^card(\\d+)$").find(d.name)?.groupValues?.get(1)?.toInt() ?: return@mapNotNull null
            val dev = File(racine, "sys/class/sound/card$n/device")
            var usb = placeUsb(runCatching { dev.canonicalPath }.getOrDefault(""))
            // No sysfs: the stream's line ("… at usb-0000:00:14.0-10.1.1.4, full speed") gives the ports.
            if (usb.isEmpty()) Regex("at usb-[^-]+-([\\d.]+)").find(lit(File(d, "stream0")).lineSequence().firstOrNull().orEmpty())
                ?.let { usb = "?-" + it.groupValues[1] }
            val produit = if (usb.isNotEmpty() && !usb.startsWith("?")) lit(File(racine, "sys/bus/usb/devices/$usb/product")) else ""
            Carte(n, lit(File(d, "id")), lit(File(d, "usbid")), usb, produit)
        }.sortedBy { it.numero }
    }

    /** The USB place of a serial port's path (« /dev/ttyUSB0 », a /dev/serial/by-id link), "" when unknown. */
    fun placeTty(racine: File, chemin: String): String {
        if (chemin.isBlank()) return ""
        // The link followed by name only: the device itself is never opened.
        val nom = runCatching { File(racine, chemin.removePrefix("/")).canonicalFile.name }.getOrNull()?.takeIf { it.startsWith("tty") }
            ?: File(chemin).name
        return placeUsb(runCatching { File(racine, "sys/class/tty/$nom/device").canonicalPath }.getOrDefault(""))
    }

    /** The rig on the same hub as [place], by its neighbours' serial numbers ("IC-9700 13012155 A" → "IC-9700"). */
    fun voisinIcom(racine: File, place: String): String {
        val hub = parent(place).takeIf { it.isNotEmpty() } ?: return ""
        val devices = File(racine, "sys/bus/usb/devices")
        for (v in devices.listFiles().orEmpty().sortedBy { it.name }) {
            if (v.name == place || !v.name.startsWith("$hub.") || v.name.substring(hub.length + 1).contains('.') || v.name.contains(':')) continue
            Regex("^(IC-?\\d{3,4}[A-Z]?)\\b").find(lit(File(v, "serial")))?.let { m ->
                val n = m.groupValues[1]
                return if (n.contains('-')) n else n.substring(0, 2) + "-" + n.substring(2)
            }
        }
        return ""
    }

    /** The friendly name of each capture mixer of [noms] (those not known are left out). */
    fun libelles(racine: File, noms: List<String>, poste: Poste): Map<String, Libelle> {
        val cartes = cartes(racine).associateBy { it.numero }
        val placesCat = poste.chemins.map { placeTty(racine, it) }.filter { it.isNotEmpty() }
        val r = LinkedHashMap<String, Libelle>()
        for (nom in noms) {
            val (n, dev) = numero(nom) ?: continue
            val c = cartes[n] ?: continue
            libelle(c, dev, nom, poste, placesCat, voisinIcom(racine, c.usb))?.let { r[nom] = it }
        }
        return r
    }

    /**
     * One card's name. [voisin]: the Icom found on its hub ("" none);
     * [placesCat]: the CAT ports' USB places.
     */
    fun libelle(c: Carte, dev: Int, nomMixer: String, poste: Poste, placesCat: List<String>, voisin: String): Libelle? {
        val produit = c.produit.ifBlank { if (c.usbId.isNotBlank()) "USB Audio" else nomMixer.substringBefore(" [").trim() }
        val suffixe = if (dev > 0) " · entrée " + (dev + 1) else ""
        val hub = parent(c.usb).takeIf { it.isNotEmpty() && !c.usb.startsWith("?") }
        val surHubCat = hub != null && placesCat.any { parent(it) == hub }
        val codecPoste = Regex("^08bb:29\\d\\d$", RegexOption.IGNORE_CASE).matches(c.usbId)
        return when {
            voisin.isNotEmpty() -> Libelle("$voisin ($produit)$suffixe", voisin)
            surHubCat && poste.nom.isNotBlank() -> Libelle(poste.nom + " ($produit)$suffixe", poste.nom)
            codecPoste && poste.icom -> Libelle("Poste Icom ? ($produit)$suffixe")
            c.usbId.isNotBlank() -> Libelle("Carte USB ($produit)$suffixe")
            else -> null
        }
    }

    /**
     * A motherboard card's name from Java's description ("…, ALC897 Analog,
     * …" → « Carte mère (ALC897) »), null when it is not one.
     */
    fun carteMere(nomMixer: String, description: String, c: Carte?): String? {
        if (c == null || c.usbId.isNotBlank()) return null
        if (!(c.id.equals("PCH", true) || c.id.startsWith("HDA", true) || c.id.equals("Generic", true) || description.contains("HDA Intel"))) return null
        val codec = description.split(", ").getOrNull(1)?.trim()?.split(' ')?.firstOrNull()?.takeIf { it.isNotBlank() }
        val dev = numero(nomMixer)?.second ?: 0
        return "Carte mère" + (codec?.let { " ($it)" } ?: "") + if (dev > 0) " · entrée " + (if (description.contains(" Alt ")) "2" else (dev + 1).toString()) else ""
    }

    /** All names: the rig's, the USB cards', the motherboard's ([descriptions]: Java's, by mixer name). */
    fun tous(racine: File, appareils: List<Pair<String, String>>, poste: Poste): Map<String, Libelle> {
        val r = LinkedHashMap(libelles(racine, appareils.map { it.first }, poste))
        val cartes = cartes(racine).associateBy { it.numero }
        for ((nom, desc) in appareils) if (nom !in r) {
            val c = numero(nom)?.first?.let { cartes[it] }
            carteMere(nom, desc, c)?.let { r[nom] = Libelle(it) }
        }
        return r
    }

    /** The rig's bands, in Hz, for the models that say them; null: any frequency. */
    fun bandesPoste(modele: String): List<LongRange>? = when (modele) {
        "IC9700" -> listOf(144_000_000L..148_000_000L, 430_000_000L..450_000_000L, 1_240_000_000L..1_300_000_000L)
        else -> null
    }

    /** Whether the rig's downlink [dlHz] is within its bands (it receives the satellite followed). */
    fun posteEntend(modele: String, dlHz: Long?): Boolean {
        if (dlHz == null || dlHz <= 0) return false
        return bandesPoste(modele)?.any { dlHz in it } ?: true
    }
}
