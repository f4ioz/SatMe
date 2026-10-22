/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.datv.SanteNoeud
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.TimeUnit

/**
 * A SatMe-Node's health, read on the node: what its system files say
 * (uptime, load, temperature, memory, the microSD's free space, the
 * network, the USB boards and their speed), `vcgencmd get_throttled` when
 * the Pi has it, each service's state, its last log lines, its version.
 * Read only: nothing here changes the node, nothing is opened but files
 * (and that one command, a few seconds at most).
 */
class SanteNoeudPc(
    private val datv: DatvPc,
    private val sdr: SdrNoeudPc?,
    private val tx: EmissionNoeudPc?,
    private val version: String = versionPc(),
    /** Where /proc and /sys are (the tests: a folder of their own). */
    private val racine: File = File("/"),
    private val vcgencmd: () -> String? = { lanceVcgencmd() },
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var precedent: Pair<Long, Long>? = null

    fun json(): JSONObject {
        val o = JSONObject().put("version", version).put("ms", horloge())
        val sys = JSONObject()
        lit("proc/uptime")?.let { SanteNoeud.uptime(it) }?.let { sys.put("uptimeS", Math.round(it)) }
        lit("proc/loadavg")?.let { SanteNoeud.charge(it) }?.takeIf { it.isNotEmpty() }?.let { sys.put("charge", JSONArray(it)) }
        sys.put("coeurs", Runtime.getRuntime().availableProcessors())
        lit("sys/class/thermal/thermal_zone0/temp")?.let { SanteNoeud.temperature(it) }?.let { sys.put("temperature", Math.round(it * 10) / 10.0) }
        lit("proc/meminfo")?.let { SanteNoeud.memoire(it) }?.let { (t, l) -> sys.put("memoire", JSONObject().put("totalKo", t).put("libreKo", l)) }
        runCatching { File(racine, ".").usableSpace / (1024 * 1024) }.getOrNull()?.takeIf { it > 0 }?.let { sys.put("disqueLibreMo", it) }
        runCatching { File(racine, ".").totalSpace / (1024 * 1024) }.getOrNull()?.takeIf { it > 0 }?.let { sys.put("disqueTotalMo", it) }
        runCatching { vcgencmd() }.getOrNull()?.let { SanteNoeud.bridage(it) }?.let { b ->
            sys.put("bridage", JSONArray(b.maintenant)).put("bridageDepuis", JSONArray(b.depuisDemarrage)).put("bridageBrut", "0x" + Integer.toHexString(b.brut))
        }
        sys.put("javaMemoireMo", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024))
        o.put("systeme", sys)
        o.put("reseau", reseau())
        o.put("usb", usb())
        o.put("services", services())
        o.put("journal", JSONArray(JournalNoeud.dernieres(30)))
        o.put("systemd", sousSystemd())
        val a = SanteNoeud.alertes(o)
        o.put("alertes", SanteNoeud.json(a))
        return o
    }

    private fun lit(chemin: String): String? = runCatching { File(racine, chemin).takeIf { it.isFile }?.readText() }.getOrNull()

    // Container bridges and veth pairs say nothing about the node's link
    private val VIRTUELLES = Regex("^(veth|br-|docker|virbr|lxc|cni|flannel)")

    private fun reseau(): JSONObject {
        val ips = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual && !VIRTUELLES.containsMatchIn(it.name) && it.inetAddresses.toList().any { a -> a is java.net.Inet4Address } }.map { n ->
                JSONObject().put("nom", n.name).put("adresses", JSONArray(n.inetAddresses.toList().filter { it is java.net.Inet4Address }.map { it.hostAddress }))
                    .put("lien", lit("sys/class/net/${n.name}/speed")?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.let { "$it Mb/s" } ?: "")
            }
        }.getOrDefault(emptyList())
        // The SDR stream's throughput since the last read.
        val t = horloge()
        val oc = sdr?.octets?.get() ?: 0L
        val p = precedent
        precedent = t to oc
        val kbps = if (p != null && t > p.first) ((oc - p.second) * 8 / (t - p.first)) else 0L
        return JSONObject().put("interfaces", JSONArray(ips)).put("fluxIqKbps", kbps)
    }

    private fun usb(): JSONArray {
        val d = File(racine, "sys/bus/usb/devices")
        fun l(f: File, n: String) = runCatching { File(f, n).takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
        return JSONArray((d.listFiles() ?: emptyArray()).sortedBy { it.name }.mapNotNull { f ->
            val v = l(f, "idVendor")?.lowercase() ?: return@mapNotNull null
            val p = l(f, "idProduct")?.lowercase() ?: return@mapNotNull null
            val nom = SanteNoeud.USB_CONNUS["$v:$p"] ?: return@mapNotNull null
            JSONObject().put("id", "$v:$p").put("nom", nom).put("vitesse", l(f, "speed")?.let { SanteNoeud.vitesseUsb(it) } ?: "")
        })
    }

    private fun services(): JSONObject {
        val e = datv.etat
        val o = JSONObject()
        o.put("datv", JSONObject().put("appareil", datv.recepteur)
            .put("etat", if (e.actif) "marche" else if (e.erreur.isNotBlank()) "erreur" else "arret").put("erreur", e.erreur)
            .put("clients", datv.clients).put("libelle", e.libelle))
        sdr?.let { s ->
            val es = s.etat
            val total = es.trames
            o.put("sdr", JSONObject().put("appareil", s.appareil)
                .put("etat", if (es.actif) "marche" else if (es.erreur.isNotBlank()) "erreur" else "arret").put("erreur", es.erreur)
                .put("client", es.client).put("centre", es.centreHz).put("debit", es.debit).put("trames", total).put("sautees", es.sautees)
                .put("pertesPct", if (total > 0) 100.0 * es.sautees / total else 0.0)
                .put("depuisS", if (es.actif) (horloge() - es.depuisMs) / 1000 else 0))
        }
        tx?.let { t ->
            val et = t.etat
            o.put("tx", JSONObject().put("appareil", t.appareil).put("autorisee", t.autorisee)
                .put("etat", if (et.emet) "marche" else if (et.erreur.isNotBlank()) "erreur" else "arret").put("erreur", et.erreur)
                .put("client", et.client).put("hz", et.frequenceHz).put("gain", et.gainDb).put("raison", et.raison)
                .put("depuisS", if (et.emet) (horloge() - et.depuisMs) / 1000 else 0))
        }
        return o
    }

    companion object {
        /** Run by systemd (its service restarts it when it ends in error): « relancer » is then safe. */
        fun sousSystemd(): Boolean = !System.getenv("INVOCATION_ID").isNullOrBlank()

        fun lanceVcgencmd(): String? {
            val p = listOf("/usr/bin/vcgencmd", "/opt/vc/bin/vcgencmd").firstOrNull { File(it).canExecute() } ?: return null
            val pr = ProcessBuilder(p, "get_throttled").redirectErrorStream(true).start()
            runCatching { pr.outputStream.close() }
            if (!pr.waitFor(3, TimeUnit.SECONDS)) { pr.destroyForcibly(); return null }
            return pr.inputStream.bufferedReader().readText()
        }
    }
}

/** The node's last log lines (what it prints), kept in memory for its health page. */
object JournalNoeud {
    private val lignes = ArrayDeque<String>()

    fun note(l: String) {
        val t = java.time.LocalTime.now().withNano(0).toString()
        synchronized(lignes) { lignes.addLast("$t $l"); while (lignes.size > 200) lignes.removeFirst() }
    }

    fun dernieres(n: Int): List<String> = synchronized(lignes) { lignes.toList().takeLast(n) }

    /** What the node prints also kept here (its standard output and errors). */
    fun capture() {
        fun tee(o: PrintStream): PrintStream = PrintStream(object : OutputStream() {
            private val b = java.io.ByteArrayOutputStream()
            override fun write(c: Int) {
                o.write(c)
                if (c == '\n'.code) { val l = b.toString("UTF-8"); b.reset(); if (l.isNotBlank()) note(l.trimEnd()) } else if (b.size() < 2000) b.write(c)
            }
            override fun flush() = o.flush()
        }, true, "UTF-8")
        System.setOut(tee(System.out)); System.setErr(tee(System.err))
    }
}
